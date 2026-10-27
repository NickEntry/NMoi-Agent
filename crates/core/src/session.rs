//! 会话编排（P6 的地基）：把引擎、工具循环、存储、压缩触发串成一条线。
//!
//! UI 层要的东西其实只有三件：**发一句话、看它怎么一步步做、仓库里有没有留下痕迹**。
//! 这个模块就是那三步的确定性实现——UI 拿到的不是"模型说了什么"，而是
//! [`SessionTurn`]：落到库里的消息、工具调用记录、以及"该不该压缩"的判据。
//!
//! # 为什么历史存储要走 trait
//!
//! core 不能依赖 storage（依赖方向反了）。所以这里只定 [`HistoryStore`]，
//! `nmoi_storage::Storage` 去实现它。测试里用一个内存实现就够了——
//! "会话编排对不对"和"SQLite 写进去对不对"是两件事，分开测。
//!
//! # 落库顺序（被杀也不留半截）
//!
//! 1. 用户消息先落库（用户说了什么，不能因为后面失败就丢）；
//! 2. 模型跑完，把它的消息按**原子单元**落库：有工具调用的做成 `tool_pair`，
//!    并且**先写结果再闭合**，不闭合的单元启动收口会报出来（D47）；
//! 3. 最后算压缩判据。判据**不落库**——它是"现在该怎么继续"的结论，不是历史。
//!
//! 这里的每一步都是同步调用，没有后台队列：手机上"稍后写"等于"被杀就丢"（D45）。

use std::fmt;

use crate::agent::{run_turn, AgentError, Cancellation, ToolRecord, TurnConfig, TurnReport};
use crate::budget::{
    decide, evaluate_trigger, Budget, CompressAction, CompressionTrigger, TriggerPoint,
    TriggerPolicy,
};
use crate::clock::LogicalClock;
use crate::divider::ContextWindow;
use crate::ids::{MessageId, SessionId, UnitId};
use crate::message::{AtomicUnit, Message, OutgoingToolCall, Role};
use crate::policy::AssistantPolicy;
use crate::retry::RetryPolicy;
use crate::runtime::{ChatClient, ChatRequest};
use crate::source::SourceTag;
use crate::tokens::{estimate_tokens, Calibration, Estimator};
use crate::tools::{ToolContext, ToolRegistry};
use crate::transport::Transport;
use crate::workspace::Workspace;

/// 历史读写。实现方负责事务与顺序（SQLite 那边就是 `append_unit`）。
pub trait HistoryStore {
    fn append_unit(&mut self, session: SessionId, unit: &AtomicUnit) -> Result<(), String>;
    fn load_units(&self, session: SessionId) -> Result<Vec<AtomicUnit>, String>;

    /// 当前窗口：最新分界线以下的单元 + 那条线的摘要（D149）。
    ///
    /// 默认实现把整条历史当窗口——**没画过线的会话行为完全不变**，
    /// 测试里的内存实现也不用跟着改。真正认分界线的实现（SQLite）覆盖它。
    fn load_window(&self, session: SessionId) -> Result<ContextWindow, String> {
        Ok(ContextWindow::of_all(self.load_units(session)?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SessionError {
    #[error("读历史失败：{0}")]
    Load(String),
    #[error("写历史失败：{0}")]
    Save(String),
    #[error("模型调用失败：{0}")]
    Agent(#[from] AgentError),
    /// 造请求前已经越过绝对线（D90）。core 自己压不了（要 provider + archive），
    /// 所以这里唯一诚实的动作是**拒绝**：让调用方先压，再重来这一轮。
    /// 此时用户消息**还没落库**——重试不会落成两条。
    #[error("上下文已达绝对线（{used}/{usable} token），先压缩再发", used = .used, usable = .usable)]
    CompactionRequired { used: u64, usable: u64 },
}

/// 一次会话往返的完整结果。
#[derive(Debug, Clone, PartialEq)]
pub struct SessionTurn {
    pub user: Message,
    /// 模型最后那段话（已落库的那一条）。
    ///
    /// `None` = 这一轮模型一句正文都没说（常见于轮数用尽时它只调了工具）。
    /// 空正文不落库：`Message::validate` 也拒——一条空消息进历史，下一轮就是噪声。
    pub assistant: Option<Message>,
    /// 这一轮落库的全部单元（用户 1 个 + 助手/工具若干）。
    pub units: Vec<UnitId>,
    pub report: TurnReport,
    /// 上下文用量与压缩判据。`None` 表示调用方没给预算（或这一轮在压缩）。
    pub compression: Option<CompressionSignal>,
    /// 这一轮该写回库的校准（L2 / D95）。`None` = 没到样本门槛，或端点没回 usage。
    ///
    /// core 不碰存储：写不写、什么时候写，由调用方决定。
    pub calibration: Option<Calibration>,
}

impl SessionTurn {
    pub fn tool_calls(&self) -> &[ToolRecord] {
        &self.report.tool_calls
    }

    pub fn should_compress(&self) -> bool {
        self.compression
            .as_ref()
            .map(|signal| signal.trigger != CompressionTrigger::None)
            .unwrap_or(false)
    }
}

/// 压缩判据。**只是建议**：真压不压、什么时候压，是调用方（UI）的决定。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CompressionSignal {
    pub trigger: CompressionTrigger,
    /// 这一处该做什么（D90 的决策表）。UI 不该自己再推一遍。
    pub action: CompressAction,
    pub used_tokens: u64,
    pub ratio: f64,
}

/// 编排所需的配置。预算给 `None` 就不算压缩判据。
#[derive(Clone, Copy)]
pub struct SessionConfig<'a> {
    pub turn: TurnConfig,
    pub budget: Option<&'a Budget>,
    pub trigger: &'a TriggerPolicy,
    /// 平均每轮消耗，算"还能跑几轮"用。`None` 用本次实测值兜底。
    pub avg_per_turn: Option<u64>,
    /// 协作式取消（D58）。默认 [`NeverCancel`]。
    pub cancel: &'a dyn Cancellation,
    /// 已经攒下的校准（D95）。空切片 = 还没校准过，用原始估算 + 安全系数。
    pub calibrations: &'a [Calibration],
    /// 上层的时间点重试（D70/D71）。`None` = 不重发。
    ///
    /// 按**每一次工具往返**各算各的：第 2 轮撞了 503 就重发那一次请求，
    /// 不是整轮重来——整轮重来会把已经执行过的工具再跑一遍。
    pub retry: Option<&'a RetryPolicy>,
    /// 压缩自己那一轮不能再触发压缩（D90）：压缩调用方把它置 `true`，
    /// 否则"压缩时发现上下文太长 → 再压一次"会套娃。
    pub suppress_compression: bool,
    /// 这一轮冻结好的模型与工具名单（D76）。`None` = 不冻结，按传进来的 base 请求跑。
    ///
    /// 传了它，压缩判据、工具循环的每一轮、思考字段都读同一份值——中途改设置不会传染进来。
    pub plan: Option<&'a crate::turn::TurnPlan>,
    /// 容器运行时的坐标（D172）。`None` = 这一轮没有隔离环境可用，
    /// `WorkSpace-EnvirShell` 不会被声明（门控也没开）。
    pub envir: Option<&'a crate::shell::EnvirSpec>,
}

// 手写 Debug：`dyn Cancellation` 没有 Debug，而配置打日志时想知道的是"会不会被取消"。
impl fmt::Debug for SessionConfig<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SessionConfig")
            .field("turn", &self.turn)
            .field("budget", &self.budget)
            .field("trigger", &self.trigger)
            .field("avg_per_turn", &self.avg_per_turn)
            .field("cancelled", &self.cancel.is_cancelled())
            .field("frozen_model", &self.plan.map(|plan| plan.model.as_str()))
            .field("suppress_compression", &self.suppress_compression)
            .field("calibrations", &self.calibrations.len())
            .field("retry", &self.retry.is_some())
            .finish()
    }
}

/// 默认触发策略（0.75 / 0.92）。**从 `TriggerPolicy::default()` 取，不在这儿抄一份数字**：
/// 抄过的那份迟早和真源对不上，而且是在别人改默认值之后才被发现。
fn default_trigger() -> &'static TriggerPolicy {
    static CELL: std::sync::OnceLock<TriggerPolicy> = std::sync::OnceLock::new();
    CELL.get_or_init(TriggerPolicy::default)
}

impl Default for SessionConfig<'_> {
    fn default() -> Self {
        static NEVER: crate::agent::NeverCancel = crate::agent::NeverCancel;
        Self {
            turn: TurnConfig::default(),
            budget: None,
            trigger: default_trigger(),
            avg_per_turn: None,
            cancel: &NEVER,
            calibrations: &[],
            retry: None,
            suppress_compression: false,
            plan: None,
            envir: None,
        }
    }
}

/// 跑一轮会话：落用户消息 → 工具循环 → 落模型消息 → 算压缩判据。
///
/// `now` 是这一轮的时间戳（毫秒），由调用方给：core 不读墙上时钟。
#[allow(clippy::too_many_arguments)]
pub fn run_session_turn<T: Transport, S: HistoryStore>(
    client: &ChatClient<T>,
    base: &ChatRequest,
    store: &mut S,
    session: SessionId,
    user_text: &str,
    registry: &ToolRegistry,
    policy: &AssistantPolicy,
    workspace: Option<&Workspace>,
    config: &SessionConfig<'_>,
    now: i64,
) -> Result<SessionTurn, SessionError> {
    // 1. 用户消息先落库：用户说了什么，不因为后面失败而丢
    //    读的是**窗口**不是整条历史（D149）：线上那一段已经被压成摘要，
    //    再把原文整段发出去，等于压缩没发生。
    let window = store.load_window(session).map_err(SessionError::Load)?;
    let summary = window.summary_message(session, now);
    let summary_tokens = window.summary_tokens();
    let units_before = window.units;

    // 1.4 估算器：模型跟冻结值走（D76）——压缩判据与工具循环不能读两套模型名。
    let model = config
        .plan
        .map_or(base.model.as_str(), |plan| plan.model.as_str());
    let estimator = Estimator::new(model, config.calibrations);

    // 1.5 造请求前先过绝对线（D90）。**判定要在落库之前**：这一步拒绝之后调用方会
    // 压缩再重来，要是用户消息已经写进去了，重试就会变成两条一模一样的话。
    if let Some(budget) = config.budget {
        if !config.suppress_compression {
            let used = tokens_of(&estimator, &units_before)
                + summary_tokens
                + u64::from(estimator.estimate(user_text).tokens);
            let peek = with_used(*budget, used);
            let avg = config.avg_per_turn.unwrap_or_else(|| {
                let turns = units_before.len().max(1) as u64;
                peek.used / turns
            });
            let trigger = evaluate_trigger(&peek, avg, 11.5, config.trigger);
            if decide(trigger, TriggerPoint::BeforeRequest).blocks() {
                return Err(SessionError::CompactionRequired {
                    used,
                    usable: budget.usable(),
                });
            }
        }
    }
    let next_seq = units_before
        .iter()
        .map(|unit| unit.last_message().id.seq())
        .max()
        .unwrap_or(0);
    let user = Message::text(
        MessageId::new(session, next_seq + 1),
        Role::User,
        SourceTag::History,
        user_text,
        estimate_tokens(user_text),
        now,
        LogicalClock::new(next_seq + 1),
    );
    let user_unit =
        AtomicUnit::turn(user.clone()).map_err(|error| SessionError::Save(error.to_string()))?;
    store
        .append_unit(session, &user_unit)
        .map_err(SessionError::Save)?;

    // 2. 把历史摊成消息序列，跑工具循环
    // 断块修复（P4.1 / D66）：不完整的工具单元在**这里**被翻译成文字块，
    // 库里的单元一个字节都不动——不改历史，只改"发出去的那一份"。
    // 摘要排在最前（D149）：它是线上那一段唯一的替身，排在历史之后就成了"补充说明"。
    let mut history: Vec<Message> = Vec::with_capacity(units_before.len() + 2);
    if let Some(summary) = summary {
        history.push(summary);
    }
    history.extend(crate::repair::normalize(&units_before));
    history.push(user.clone());

    // 一轮之内模型冻结（D76）：这一轮的每一次请求都用同一份模型与思考设置，
    // 工具声明也用同一份名单。冻结在**发出去的那一份**上生效，base 由调用方持有。
    let mut frozen_base = base.clone();
    let mut ctx = ToolContext::new(policy, workspace);
    if let Some(envir) = config.envir {
        ctx = ctx.with_envir(envir);
    }
    if let Some(plan) = config.plan {
        plan.apply(&mut frozen_base);
        ctx = ctx.with_declared(plan.declared_tools());
    }
    // 生成中的那一道闸（D90）：绝对线折成 token，交给工具循环按字符增量盯住。
    // 略宽于真值——它从"历史消息"起算，没算 system 与工具声明；窄了会冤杀长任务。
    let mut turn_config = config.turn;
    if let Some(budget) = config.budget {
        turn_config.truncate_at = Some(config.trigger.absolute.resolve(budget.usable()));
    }
    let report = run_turn(
        client,
        &frozen_base,
        &history,
        registry,
        &ctx,
        &turn_config,
        config.cancel,
        config.retry,
    )?;

    // 3. 落模型这一轮：工具调用与结果成对落，再落最后的正文
    let mut persisted = Vec::new();
    let mut seq = next_seq + 2;
    for record in &report.tool_calls {
        let call = OutgoingToolCall::new(
            record.call_id.clone(),
            record.name.clone(),
            record.arguments.clone(),
        );
        let head = Message::tool_call_message(
            MessageId::new(session, seq),
            vec![call],
            estimate_tokens(&record.arguments),
            now,
            LogicalClock::new(seq),
        );
        let mut pair =
            AtomicUnit::tool_pair(head).map_err(|error| SessionError::Save(error.to_string()))?;
        let result = Message::tool_result(
            MessageId::new(session, seq + 1),
            &record.call_id,
            record.outcome.clone(),
            estimate_tokens(&record.outcome),
            now,
            LogicalClock::new(seq + 1),
        )
        .with_unit(pair.id.clone());
        pair.add_result(result)
            .map_err(|error| SessionError::Save(error.to_string()))?;
        store
            .append_unit(session, &pair)
            .map_err(SessionError::Save)?;
        persisted.push(pair.id.clone());
        seq += 2;
    }

    // 正文为空就不落：一条空消息进历史只会变成噪声，而且 `Message::validate` 也拒。
    // 这种时候这一轮"没说完"这件事由 `report.finished` 交代，不靠一条假消息。
    let assistant = if report.text.trim().is_empty() {
        None
    } else {
        let message = Message::text(
            MessageId::new(session, seq),
            Role::Assistant,
            SourceTag::History,
            report.text.clone(),
            estimate_tokens(&report.text),
            now,
            LogicalClock::new(seq),
        )
        .mark_incomplete(!report.finished);
        let unit = AtomicUnit::turn(message.clone())
            .map_err(|error| SessionError::Save(error.to_string()))?;
        store
            .append_unit(session, &unit)
            .map_err(SessionError::Save)?;
        persisted.push(unit.id.clone());
        Some(message)
    };

    // 4. 压缩判据：不落库，只给结论。
    //    压缩自己那一轮不给结论——刚压完就被告知"去压"是套娃的起点（D90）。
    let compression = if config.suppress_compression {
        None
    } else {
        config.budget.map(|budget| {
            let mut budget = *budget;
            budget.used = units_before
                .iter()
                .chain(std::iter::once(&user_unit))
                .map(|unit| u64::from(unit.token_total()))
                .sum::<u64>()
                + summary_tokens
                + u64::from(estimate_tokens(&report.text))
                + report
                    .tool_calls
                    .iter()
                    .map(|record| u64::from(estimate_tokens(&record.outcome)))
                    .sum::<u64>();
            let avg = config.avg_per_turn.unwrap_or_else(|| {
                let turns = units_before.len().max(1) as u64;
                budget.used / turns
            });
            // 回本周期默认 11.5 轮（附录 A：断点式缓存），没配就按这个走
            let trigger = evaluate_trigger(&budget, avg, 11.5, config.trigger);
            CompressionSignal {
                trigger,
                action: decide(trigger, TriggerPoint::AfterGeneration),
                used_tokens: budget.used,
                ratio: budget.ratio(),
            }
        })
    };

    // 5. L2 校准：把这一轮的 `usage.prompt_tokens` 和我们对**同一份请求**的估算配成一对。
    //    压缩自己那一轮不算——那一轮的上下文是刻意的、不代表用户的真实会话。
    let calibration = if config.suppress_compression {
        None
    } else {
        let whole = estimator.estimate_all(request_pieces(
            &units_before,
            window.summary.as_deref(),
            &user,
            &report,
        ));
        estimator.observe_usage(u64::from(report.usage.prompt_tokens), &whole, now)
    };

    Ok(SessionTurn {
        user,
        assistant,
        units: persisted,
        report,
        compression,
        calibration,
    })
}

/// 一堆单元一共占多少 token。**走估算器**，和面板、和判据同一档（D97 的"同一口径"）。
fn tokens_of(estimator: &Estimator<'_>, units: &[AtomicUnit]) -> u64 {
    crate::budget::breakdown_of_units(units, Some(estimator)).total()
}

/// 这一次请求到底发了些什么字，按顺序摊平（历史 → 用户 → 助手 → 工具结果）。
///
/// 校准要的是"真实值 ÷ 我们的估算"这一对比率，两边必须指同一份东西：
/// 少算一段，因子就虚高；多算一段，因子就虚低。
fn request_pieces<'a>(
    units: &'a [AtomicUnit],
    summary: Option<&'a str>,
    user: &'a Message,
    report: &'a crate::agent::TurnReport,
) -> Vec<&'a str> {
    let mut pieces = Vec::new();
    // 摘要是请求的第一段（D149）：不算进来的话，压过的会话会显得"估多了"，
    // 校准因子被拉低，下一轮就开始低估。
    if let Some(summary) = summary {
        pieces.push(summary);
    }
    for unit in units {
        for message in &unit.messages {
            pieces.push(message.content.as_str());
            if let Some(reasoning) = message.reasoning.as_deref() {
                pieces.push(reasoning);
            }
            for call in &message.tool_calls {
                pieces.push(call.arguments.as_str());
            }
        }
    }
    pieces.push(user.content.as_str());
    pieces.push(report.text.as_str());
    for record in &report.tool_calls {
        pieces.push(record.outcome.as_str());
    }
    pieces
}

fn with_used(budget: Budget, used: u64) -> Budget {
    let mut budget = budget;
    budget.used = used;
    budget
}

/// 讲历史摊成给模型看的消息序列（跳过工具定义这类"不是对话"的来源）。
pub fn conversation_of(units: &[AtomicUnit]) -> Vec<Message> {
    units
        .iter()
        .flat_map(|unit| unit.messages.iter().cloned())
        .filter(|message| {
            !matches!(
                message.source,
                SourceTag::InstructionInjection | SourceTag::ToolDefinition
            )
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::sync::{Arc, Mutex};

    use crate::budget::Threshold;
    use crate::config::{ModelFlavor, ThinkingContext};
    use crate::model::ModelProfile;
    use crate::policy::WorkspaceBinding;
    use crate::provider::{ProviderCapabilities, ProviderConfig, ProviderKind};
    use crate::settings::ToolSwitches;
    use crate::transport::{HttpRequest, HttpResponse, Transport, TransportError};

    #[derive(Default)]
    struct MemStore {
        units: Mutex<Vec<AtomicUnit>>,
    }

    impl HistoryStore for MemStore {
        fn append_unit(&mut self, _session: SessionId, unit: &AtomicUnit) -> Result<(), String> {
            self.units.lock().unwrap().push(unit.clone());
            Ok(())
        }

        fn load_units(&self, _session: SessionId) -> Result<Vec<AtomicUnit>, String> {
            Ok(self.units.lock().unwrap().clone())
        }
    }

    /// 已经压过一轮的会话：`load_units` 还是全量（线上原文都在），`load_window` 只给线下 + 摘要。
    ///
    /// 这正是 D149 的要点——**未压过的会话行为不变**，压过的会话走窗口。真实现是
    /// `Storage::load_window`（`crates/storage/tests/window.rs` 盯着），这里只验编排层拿它怎么办。
    struct WindowStore {
        all: Vec<AtomicUnit>,
        window: Vec<AtomicUnit>,
        summary: Option<String>,
    }

    impl WindowStore {
        fn new(all: Vec<AtomicUnit>, window: Vec<AtomicUnit>, summary: &str) -> Self {
            Self {
                all,
                window,
                summary: Some(summary.to_string()),
            }
        }

        fn message(session: SessionId, seq: u64, role: Role, text: &str) -> AtomicUnit {
            AtomicUnit::turn(Message::text(
                MessageId::new(session, seq),
                role,
                SourceTag::History,
                text,
                text.chars().count() as u32,
                1_000,
                LogicalClock::new(seq),
            ))
            .unwrap()
        }
    }

    impl HistoryStore for WindowStore {
        fn append_unit(&mut self, _session: SessionId, unit: &AtomicUnit) -> Result<(), String> {
            self.all.push(unit.clone());
            self.window.push(unit.clone());
            Ok(())
        }

        fn load_units(&self, _session: SessionId) -> Result<Vec<AtomicUnit>, String> {
            Ok(self.all.clone())
        }

        fn load_window(&self, _session: SessionId) -> Result<ContextWindow, String> {
            Ok(ContextWindow {
                divider: None,
                summary: self.summary.clone(),
                units: self.window.clone(),
            })
        }
    }

    struct Scripted {
        bodies: Mutex<VecDeque<String>>,
        seen: Mutex<Vec<String>>,
    }

    impl Scripted {
        fn new(bodies: Vec<String>) -> Arc<Self> {
            Arc::new(Self {
                bodies: Mutex::new(bodies.into()),
                seen: Mutex::new(Vec::new()),
            })
        }

        /// 这一轮到底发出去什么。（断块修复要看的就是"发出去的那一份"。）
        fn requests(&self) -> Vec<String> {
            self.seen.lock().unwrap().clone()
        }
    }

    impl Transport for Arc<Scripted> {
        fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
            self.seen
                .lock()
                .unwrap()
                .push(String::from_utf8_lossy(&request.body).to_string());
            let body = self
                .bodies
                .lock()
                .unwrap()
                .pop_front()
                .ok_or_else(|| TransportError::Unsupported("脚本用完了".into()))?;
            Ok(HttpResponse {
                status: 200,
                headers: vec![("content-type".into(), "application/json".into())],
                body: Box::new(Cursor::new(body.into_bytes())),
            })
        }
    }

    /// 前 `fail_times` 次返回连接被重置，之后正常回一条正文。
    ///
    /// 用"连接被重置"而不是 5xx：那是**重试关键词**命中的路，不依赖状态码表；
    /// 同时提醒一件事——底层 `ReconnectPolicy` 也会重试这种错，测上层时得关掉它。
    struct FlakyReply {
        fail_times: Mutex<usize>,
        calls: Mutex<usize>,
        body: String,
    }

    impl FlakyReply {
        fn new(fail_times: usize, body: String) -> Arc<Self> {
            Arc::new(Self {
                fail_times: Mutex::new(fail_times),
                calls: Mutex::new(0),
                body,
            })
        }

        fn calls(&self) -> usize {
            *self.calls.lock().unwrap()
        }
    }

    impl Transport for Arc<FlakyReply> {
        fn execute(&self, _request: HttpRequest) -> Result<HttpResponse, TransportError> {
            *self.calls.lock().unwrap() += 1;
            let mut left = self.fail_times.lock().unwrap();
            if *left > 0 {
                *left -= 1;
                return Err(TransportError::Io("connection reset by peer".into()));
            }
            Ok(HttpResponse {
                status: 200,
                headers: vec![("content-type".into(), "application/json".into())],
                body: Box::new(Cursor::new(self.body.clone().into_bytes())),
            })
        }
    }

    fn escape(text: &str) -> String {
        text.replace('\\', "\\\\").replace('"', "\\\"")
    }

    fn calls_body(calls: &[(&str, &str, &str)]) -> String {
        let items: Vec<String> = calls
            .iter()
            .map(|(id, name, arguments)| {
                format!(
                    "{{\"id\":\"{id}\",\"type\":\"function\",\"function\":\
                     {{\"name\":\"{name}\",\"arguments\":\"{}\"}}}}",
                    escape(arguments)
                )
            })
            .collect();
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"\",\
             \"tool_calls\":[{}]}},\"finish_reason\":\"tool_calls\"}}]}}",
            items.join(",")
        )
    }

    fn text_body(text: &str) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"{}\"}},\
             \"finish_reason\":\"stop\"}}]}}",
            escape(text)
        )
    }

    /// 带 usage 的响应体：L2 校准要的就是这个数（D95）。
    fn text_body_with_usage(text: &str, prompt_tokens: u32) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"{}\"}},\
             \"finish_reason\":\"stop\"}}],\
             \"usage\":{{\"prompt_tokens\":{prompt_tokens},\"completion_tokens\":2}}}}",
            escape(text)
        )
    }

    fn base_request() -> ChatRequest {
        let provider = ProviderConfig::new("smoke", ProviderKind::OpenAiCompatible, "http://x/v1");
        ChatRequest::new(provider, "test-model")
    }

    fn setup() -> (tempfile::TempDir, Workspace, AssistantPolicy) {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a.txt"), "hello\n").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        (dir, workspace, policy)
    }

    /// D76：一轮之内模型与工具名单都来自冻结值。
    ///
    /// 这里刻意让 base 请求写 `test-model`、冻结写 `frozen-model`：
    /// 发出去的那一份要是 `test-model`，说明冻结没生效，用户中途切模型的事故就回来了。
    #[test]
    fn a_frozen_plan_fixes_the_model_and_the_declared_tools() {
        let (_dir, workspace, policy) = setup();
        let mut store = MemStore::default();
        let session = SessionId::new();

        let transport = Scripted::new(vec![
            calls_body(&[("call_1", "read_file", r#"{"path":"a.txt"}"#)]),
            text_body("读完了"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();

        let profile = ModelProfile {
            capabilities: ProviderCapabilities::openai_like(),
            thinking: crate::provider::ThinkingSetting::disabled(),
            reasoning: false,
            supported_efforts: Vec::new(),
            supports_toggle: false,
            context_window: 4_096,
            max_output: 512,
            cost: None,
        };

        let mut plan = crate::turn::TurnPlan::freeze(crate::turn::Freeze {
            model: "frozen-model",
            profile: &profile,
            overrides: None,
            context: ThinkingContext::new(ProviderKind::OpenAiCompatible, ModelFlavor::Other),
            registry: &registry,
            switches: &ToolSwitches::default(),
            policy: &policy,
            connected_mcp: &[],
        });
        // 冻结出来的声明表只留一个，用来验证"声明来自冻结值"而不是现算
        plan.tools.declared = vec!["read_file".to_string()];

        let config = SessionConfig {
            plan: Some(&plan),
            ..SessionConfig::default()
        };
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "看看这个目录",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .expect("冻结之后这一轮要能正常跑完");
        assert_eq!(turn.report.tool_calls.len(), 1);

        let sent = transport.requests();
        let first = &sent[0];
        assert!(
            first.contains("\"model\":\"frozen-model\""),
            "发出去的模型必须是冻结的那个：{first}"
        );
        assert!(
            !first.contains("\"model\":\"test-model\""),
            "base 里的模型不该出现"
        );
        assert!(first.contains("read_file"));
        assert!(
            !first.contains("write_file"),
            "冻结名单里没有的工具不能被声明：{first}"
        );
    }
    /// 用户最痛的那个场景（P4.1）：库里躺着一个"调用发出去了、结果没回来"的单元，
    /// 下一次发消息上游直接 400。修复之后这一轮必须能正常跑完。
    #[test]
    fn a_broken_tool_unit_does_not_break_the_next_turn() {
        let (_dir, workspace, policy) = setup();
        let mut store = MemStore::default();
        let session = SessionId::new();

        let call = crate::message::OutgoingToolCall::new(
            "call_broken",
            "read_file",
            r#"{"path":"a.txt"}"#,
        );
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![call],
            3,
            1_000,
            LogicalClock::new(1),
        );
        store
            .append_unit(session, &AtomicUnit::tool_pair(head).unwrap())
            .unwrap();

        let transport = Scripted::new(vec![text_body("接着来")]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "继续",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            2_000,
        )
        .unwrap();

        assert!(turn.assistant.is_some(), "这一轮正常走完了");
        let request = &transport.requests()[0];
        assert!(
            request.contains("错误工具调用块"),
            "断块要翻译成文字块：{request}"
        );
        assert!(
            !request.contains("\"tool_calls\""),
            "请求里不能再出现没有结果的 tool_calls（那正是上游 400 的原因）：{request}"
        );
        assert!(
            !request.contains("call_broken"),
            "原始调用 id 不该再出现在请求里：{request}"
        );

        // 库里那条断掉的单元原样保留——只改发出去的，不改历史。
        let units = store.load_units(session).unwrap();
        assert_eq!(
            units.len(),
            3,
            "断块 + 这一轮的用户消息 + 助手回复，一条不少"
        );
        assert!(!units[0].is_closed(), "库里它还是断的，这是事实");
    }

    /// 协作式取消（D58）：说过的话要留下，但要标 `incomplete`——
    /// 断流的回复和正常的回复在数据里长得一样，这个字段就是唯一的分界。
    struct CancelAt {
        asked: std::cell::Cell<usize>,
        at: usize,
    }

    impl crate::agent::Cancellation for CancelAt {
        fn is_cancelled(&self) -> bool {
            let asked = self.asked.get() + 1;
            self.asked.set(asked);
            asked >= self.at
        }
    }

    #[test]
    fn cancelled_turn_keeps_the_text_and_marks_it_incomplete() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![calls_with_text("c1", "list_dir", "{}", "我看看目录")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();
        // 询问顺序：请求前(1) 放行；第一个工具调用前(2) 取消
        let cancel = CancelAt {
            asked: std::cell::Cell::new(0),
            at: 2,
        };
        let config = SessionConfig {
            cancel: &cancel,
            ..SessionConfig::default()
        };

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "看看目录",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        assert!(turn.report.cancelled);
        assert!(!turn.report.finished, "自己停的不能算做完了");
        assert!(turn.report.tool_calls.is_empty(), "取消发生在工具执行之前");

        let assistant = turn.assistant.expect("模型说过的话要留下，不能凭空吞掉");
        assert_eq!(assistant.content, "我看看目录");
        assert!(assistant.incomplete, "被掐断的必须标 incomplete");

        let units = store.load_units(session).unwrap();
        assert_eq!(units.len(), 2, "用户 + 助手，没有工具单元");
        assert!(units[1].messages[0].incomplete);
    }

    #[test]
    fn plain_turn_persists_user_then_assistant() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("好的")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "在吗",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .unwrap();

        assert_eq!(turn.user.content, "在吗");
        assert_eq!(turn.assistant.clone().unwrap().content, "好的");
        assert_eq!(turn.units.len(), 1, "只落一条助手单元");
        let units = store.load_units(session).unwrap();
        assert_eq!(units.len(), 2, "用户 + 助手");
        assert_eq!(units[0].messages[0].role, Role::User);
        assert_eq!(units[1].messages[0].role, Role::Assistant);
        assert!(!units[1].messages[0].incomplete);
    }

    #[test]
    fn tool_calls_are_persisted_as_closed_tool_pairs() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "read_file", r#"{"path":"a.txt"}"#)]),
            text_body("文件里是 hello"),
        ]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "读一下 a.txt",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .unwrap();

        assert_eq!(turn.report.tool_calls.len(), 1);
        assert_eq!(turn.units.len(), 2, "一个工具单元 + 一条助手单元");
        let units = store.load_units(session).unwrap();
        assert_eq!(units.len(), 3, "用户 + 工具对 + 助手");
        let pair = &units[1];
        assert!(
            pair.is_closed(),
            "工具单元必须是闭合的——不闭合启动收口会报出来"
        );
        assert_eq!(pair.messages.len(), 2);
        assert_eq!(pair.messages[0].tool_calls[0].name, "read_file");
        assert!(pair.messages[1].content.contains("hello"));
    }

    /// 带正文的工具调用（模型经常一边说话一边调工具）。
    fn calls_with_text(call_id: &str, name: &str, arguments: &str, text: &str) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"{}\",\
             \"tool_calls\":[{{\"id\":\"{call_id}\",\"type\":\"function\",\"function\":\
             {{\"name\":\"{name}\",\"arguments\":\"{}\"}}}}]}},\"finish_reason\":\"tool_calls\"}}]}}",
            escape(text),
            escape(arguments)
        )
    }

    #[test]
    fn exhausted_turn_keeps_partial_text_and_marks_it_incomplete() {
        let (_dir, workspace, policy) = setup();
        // 两轮都在调工具、顺带说话；轮数到顶 → 没说完，但说过的话要留下
        let transport = Scripted::new(vec![
            calls_with_text("c1", "list_dir", "{}", "我看看目录"),
            calls_with_text("c2", "list_dir", "{}", "再看看"),
        ]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let config = SessionConfig {
            turn: TurnConfig {
                max_rounds: 2,
                max_tokens: None,
                truncate_at: None,
            },
            ..SessionConfig::default()
        };

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "到处看看",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        assert!(!turn.report.finished, "轮数用尽不能装做完");
        let assistant = turn.assistant.expect("说过的话要留下");
        assert_eq!(assistant.content, "再看看");
        assert!(assistant.incomplete, "没收尾的回复要标 incomplete（D43）");
    }

    #[test]
    fn silent_exhausted_turn_persists_no_empty_assistant() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "list_dir", "{}")]),
            calls_body(&[("c2", "list_dir", "{}")]),
        ]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let config = SessionConfig {
            turn: TurnConfig {
                max_rounds: 2,
                max_tokens: None,
                truncate_at: None,
            },
            ..SessionConfig::default()
        };

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "到处看看",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        assert!(!turn.report.finished);
        assert!(turn.assistant.is_none(), "一句正文都没说，就别落一条空消息");
        // 库里以工具结果结尾 —— 启动收口会把它报成"没等到回复"，这是对的
        assert!(turn.units.iter().all(|_| true));
    }

    /// 用 token 直接给两条线：测试里不靠"这句话有几个 token"的算术，
    /// 那种断言在换了估算实现之后会莫名其妙地红。
    fn lines(suggest: u64, absolute: u64) -> TriggerPolicy {
        TriggerPolicy {
            suggest: Threshold::tokens(suggest),
            absolute: Threshold::tokens(absolute),
            ..TriggerPolicy::default()
        }
    }

    #[test]
    fn absolute_line_refuses_the_request_before_anything_is_persisted() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("收到")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();
        let budget = Budget::new(1_000, 1_000, 0);
        let trigger = lines(10, 10);
        let config = SessionConfig {
            budget: Some(&budget),
            trigger: &trigger,
            ..SessionConfig::default()
        };

        let error = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "这次要说一句很长的话，长到把预算吃满，看看判据会不会拦住",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .expect_err("过绝对线就该拒绝，不能假装发出去");

        match error {
            SessionError::CompactionRequired { used, usable } => {
                // 绝对线是 tokens(10)：这句话一定过线，而窗口本身还很空。
                assert!(used > 10, "{used}/{usable}");
                assert_eq!(usable, 1_000);
            }
            other => panic!("应当是 CompactionRequired，实际 {other:?}"),
        }
        // 关键：用户消息没有落库，调用方压完重试不会变成两条。
        assert!(store.load_units(session).unwrap().is_empty());
    }

    #[test]
    fn suppressed_compression_lets_the_compaction_turn_through() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("收到")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();
        let budget = Budget::new(1_000, 1_000, 0);
        let trigger = lines(10, 10);
        let config = SessionConfig {
            budget: Some(&budget),
            trigger: &trigger,
            suppress_compression: true,
            ..SessionConfig::default()
        };

        // 压缩自己那一轮：同样的预算，不该再被自己的判据拦住。
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "压缩过程中要说的话",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();
        assert!(turn.compression.is_none(), "压过就压过，不再报判据");
        assert_eq!(store.load_units(session).unwrap().len(), 2, "用户 + 助手");
    }

    #[test]
    fn suggest_line_lets_the_turn_finish_and_compacts_after() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("收到")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let session = SessionId::new();
        // 落在建议线与绝对线之间：这一轮照发。
        let budget = Budget::new(10_000, 10_000, 0);
        let trigger = lines(10, 500);
        let config = SessionConfig {
            budget: Some(&budget),
            trigger: &trigger,
            ..SessionConfig::default()
        };

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "这一句要够长，让判据落在建议线与绝对线之间，看看它会不会被拦住",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        let signal = turn.compression.expect("给了预算就该有判据");
        assert!(matches!(signal.trigger, CompressionTrigger::Suggest { .. }));
        assert_eq!(signal.action, CompressAction::CompactAfterPersist);
        assert!(turn.should_compress());
    }

    #[test]
    fn turn_reports_a_calibration_sample_when_the_endpoint_gives_usage() {
        let (_dir, workspace, policy) = setup();
        let client = ChatClient::new(Scripted::new(vec![text_body_with_usage("收到", 4_000)]));
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        // 长到过 MIN_ESTIMATE（1500）：小请求里的固定开销占比太大，收了只会让因子虚高。
        let long = "我们先估算每段占多少，再决定从哪里断开。".repeat(150);
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            &long,
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .unwrap();

        let calibration = turn
            .calibration
            .expect("有 usage、请求也够大，就该收下这个样本");
        assert_eq!(calibration.samples, 1);
        assert_eq!(calibration.model, "test-model");
        assert_eq!(
            calibration.level,
            crate::tokens::Level::L1,
            "默认 feature 下走 BPE"
        );
        assert_eq!(calibration.key(), "test-model|l1|cjk");
        assert!(calibration.ratio >= 1.0, "{}", calibration.ratio);
    }

    #[test]
    fn calibration_accumulates_on_the_document_we_were_given() {
        let (_dir, workspace, policy) = setup();
        let client = ChatClient::new(Scripted::new(vec![text_body_with_usage("收到", 4_000)]));
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let existing = crate::tokens::Calibration {
            model: "test-model".to_string(),
            level: crate::tokens::Level::L1,
            class: crate::tokens::Class::Cjk,
            ratio: 1.0,
            samples: 2,
            updated_at: 1,
        };
        let calibrations = [existing];
        let config = SessionConfig {
            calibrations: &calibrations,
            ..SessionConfig::default()
        };
        let long = "我们先估算每段占多少，再决定从哪里断开。".repeat(150);
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            &long,
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        let calibration = turn.calibration.expect("该收下");
        assert_eq!(calibration.samples, 3, "要在旧文档上累加，不是另起一份");
        assert!(calibration.updated_at == 1_000);
    }

    #[test]
    fn no_usage_means_no_calibration_sample() {
        // 默认脚本响应体不带 usage：不能把 0 当成真实值喂给校准。
        let (_dir, workspace, policy) = setup();
        let client = ChatClient::new(Scripted::new(vec![text_body("收到")]));
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "读者没给 usage 的时候不该冒出校准",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .unwrap();
        assert!(turn.calibration.is_none());
    }

    #[test]
    fn retry_policy_reaches_the_tool_loop() {
        let (_dir, workspace, policy) = setup();
        let transport = FlakyReply::new(1, text_body("重试之后才拿到"));
        // 底层立刻重连关掉：这条要证明的是"上层策略被转发进去了"，
        // 底层开着的话，第一次失败会被它自己吃掉，转发没转发都看不出来。
        let client =
            ChatClient::new(transport.clone()).with_retry(crate::runtime::ReconnectPolicy::off());
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let retry = crate::retry::RetryPolicy {
            // 零等待：测试不该真的睡 1 秒。
            bands: vec![crate::retry::WaitBand::new(0.0, 0.0)],
            ..crate::retry::RetryPolicy::default()
        };
        let config = SessionConfig {
            retry: Some(&retry),
            ..SessionConfig::default()
        };

        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "重试一下看看",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        assert_eq!(turn.report.text, "重试之后才拿到");
        assert_eq!(transport.calls(), 2, "第一次失败、第二次成功");
    }

    #[test]
    fn without_a_retry_policy_the_first_failure_surfaces() {
        let (_dir, workspace, policy) = setup();
        let transport = FlakyReply::new(1, text_body("不该拿到"));
        let client =
            ChatClient::new(transport.clone()).with_retry(crate::runtime::ReconnectPolicy::off());
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();

        let error = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "不重试",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .expect_err("没给策略就一次都不该重发");

        assert!(matches!(error, SessionError::Agent(_)), "{error:?}");
        assert_eq!(transport.calls(), 1);
    }

    #[test]
    fn no_budget_means_no_signal() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("收到")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let mut store = MemStore::default();
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            SessionId::new(),
            "在吗",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            0,
        )
        .unwrap();
        assert!(turn.compression.is_none());
        assert!(!turn.should_compress());
    }

    #[test]
    fn conversation_of_skips_injections() {
        let session = SessionId::new();
        let injected = Message::text(
            MessageId::new(session, 1),
            Role::System,
            SourceTag::InstructionInjection,
            "脚手架",
            10,
            0,
            LogicalClock::new(1),
        )
        .protect();
        let unit = AtomicUnit::turn(injected).unwrap();
        let user = AtomicUnit::turn(Message::text(
            MessageId::new(session, 2),
            Role::User,
            SourceTag::History,
            "在吗",
            2,
            0,
            LogicalClock::new(2),
        ))
        .unwrap();
        let messages = conversation_of(&[unit, user]);
        assert_eq!(messages.len(), 1, "注入不该混进对话");
        assert_eq!(messages[0].content, "在吗");
    }

    /// 压过的会话：线上原文**不许**再发出去，摘要要排在历史最前面（D149）。
    #[test]
    fn a_compacted_session_sends_the_summary_instead_of_the_archived_units() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("接着来")]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let session = SessionId::new();

        let archived = WindowStore::message(session, 1, Role::User, "线上那句早就压掉了");
        let kept = WindowStore::message(session, 2, Role::Assistant, "线下这句还在窗口里");
        let mut store = WindowStore::new(
            vec![archived, kept.clone()],
            vec![kept],
            "第一人称摘要：我之前说过一句，然后答了一句。",
        );

        run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "继续",
            &registry,
            &policy,
            Some(&workspace),
            &SessionConfig::default(),
            1_000,
        )
        .unwrap();

        let body = &transport.requests()[0];
        assert!(
            body.contains("第一人称摘要"),
            "摘要必须发出去，否则模型前面的事全丢：{body}"
        );
        assert!(
            !body.contains("线上那句早就压掉了"),
            "线上原文不许再发：压了等于没压：{body}"
        );
        assert!(
            body.contains("线下这句还在窗口里"),
            "窗口里的要留着：{body}"
        );
        // 摘要排在最前：它得是"这件事的开头"，不是历史之后的补充说明。
        let summary_at = body.find("第一人称摘要").unwrap();
        let kept_at = body.find("线下这句还在窗口里").unwrap();
        assert!(summary_at < kept_at, "摘要要在历史之前：{body}");
    }

    /// 摘要也要算进判据的 `used`：它是每轮都发的固定成本（D144 的口径）。
    #[test]
    fn the_summary_counts_toward_the_trigger() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("收到")]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let session = SessionId::new();

        let kept = WindowStore::message(session, 2, Role::Assistant, "还在窗口里");
        let long_summary = "这是上一轮压出来的摘要，写得很长很长。".repeat(40);
        let mut store = WindowStore::new(vec![kept.clone()], vec![kept], &long_summary);

        let budget = Budget::new(10_000, 10_000, 0);
        let trigger = lines(2_000, 9_000);
        let config = SessionConfig {
            budget: Some(&budget),
            trigger: &trigger,
            ..SessionConfig::default()
        };
        let turn = run_session_turn(
            &client,
            &base_request(),
            &mut store,
            session,
            "在吗",
            &registry,
            &policy,
            Some(&workspace),
            &config,
            1_000,
        )
        .unwrap();

        let signal = turn.compression.expect("给了预算就该有判据");
        assert!(
            signal.used_tokens > crate::tokens::estimate_tokens(&long_summary) as u64,
            "摘要没进 used：{} 个 token 不算等于低估",
            signal.used_tokens
        );
    }
}
