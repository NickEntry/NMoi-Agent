//! NMoi-Agent 核心 crate。
//!
//! 边界（计划 §3.2）：这里只有无头引擎与事件流，不含任何 UI 状态。
//! UI 通过 [`events::EventStream`] 订阅，通过 [`engine::Engine`] 提交输入。

pub mod agent;
pub mod anchor;
pub mod archive;
pub mod budget;
pub mod clock;
pub mod compress;
pub mod config;
pub mod degenerate;
pub mod divider;
pub mod edit;
pub mod engine;
pub mod events;
pub mod facts;
pub mod form;
pub mod gate;
pub mod iconpack;
pub mod icons;
pub mod ids;
pub mod lorebook;
pub mod message;
pub mod model;
pub mod policy;
pub mod provider;
pub mod redact;
pub mod repair;
pub mod retry;
pub mod roles;
pub mod router;
pub mod runtime;
pub mod session;
pub mod settings;
pub mod shell;
pub mod skills;
pub mod source;
pub mod speech;
pub mod sse;
pub mod stream;
pub mod tokens;
pub mod tools;
pub mod transport;
pub mod turn;
pub mod vision;
pub mod workspace;
pub mod zip;

pub use agent::{
    ensure_non_empty, run_turn, system_message as agent_system_message, AgentError, ToolRecord,
    TurnConfig, TurnReport, DEFAULT_SYSTEM,
};
pub use archive::{ArchiveEntry, ArchiveError, ArchiveStat, ArchiveStore, MemoryArchive, Origin};
pub use budget::{
    breakdown_of_units, decide, is_mcp_tool, segment_of, Breakdown, BreakdownRow, Budget,
    CacheProfile, CacheScope, CompressAction, CompressionTrigger, Segment, Threshold,
    ThresholdMode, TriggerPoint, TriggerPolicy, Usage,
};
pub use clock::{ClockError, LogicalClock};
pub use compress::runner::{
    drive as drive_compression, material as compress_material, scaffold as compress_scaffold,
    LoopConfig, LoopReport, SCAFFOLD,
};
pub use compress::{
    check_coverage, parse_command as parse_compress_command, pick_key,
    tool_specs as compress_tools, AppendReport, CompressError, CompressionJob, CompressionLimits,
    CompressionOutcome, CompressionTrace, CoverageReport, Draft, DraftCommand, DraftError,
    DraftLimits, JobState, OpRecord, ReadSlice, RequireKind, Requirement, ToolReply,
    DRAFT_APPEND_TOOL, DRAFT_DONE_TOOL, DRAFT_EDIT_TOOL, DRAFT_READ_TOOL, DRAFT_TOOLS,
};
pub use config::{
    default_thinking_field, Mark, ModelFlavor, ModelOverrides, OverrideState, ThinkingChoice,
    ThinkingContext,
};
pub use divider::{ContextWindow, Divider, DividerError, DividerId, DividerKind};
pub use edit::{apply_edit, set_locked, BlockSelector, EditError, EditOutcome, EditRequest};
pub use events::{Event, EventBus, EventEnvelope, EventStream, StreamError};
pub use facts::{
    best_key, extract as extract_facts, scan as scan_facts, Fact, FactKind, Severity, Verdict,
};
pub use gate::{
    gate_instructions, gate_world_books, mcp_server_of, plan_tools, visible_skills, ToolDrop,
    ToolPlan, WorldBookGate,
};
pub use ids::{ArchiveRef, MessageId, SessionId, SnapshotId, UnitId};
pub use lorebook::{Injection, InjectionRole, Lorebook, LorebookData, LorebookEntry, Position};
pub use message::{AtomicUnit, ContentForm, Message, OutgoingToolCall, Role, UnitError, UnitKind};
pub use model::{parse_model_name, MatchSource, ModelProfile, ModelRegistry, ResolvedModel};
pub use policy::{AssistantPolicy, Capability, DenyReason, WorkspaceBinding};
pub use provider::{
    BodyOverrides, ProviderCapabilities, ProviderConfig, ProviderKind, ThinkingEffort,
    ThinkingField, ThinkingSetting,
};
pub use retry::{
    wait, Attempt, CodeList, Decision, Entropy, GiveUpReason, KeywordList, PartialDecision,
    RetryList, RetryPolicy, WaitBand, WaitOutcome,
};
pub use roles::{
    run as run_role, sanitize_title, ModelRole, RoleCall, RoleConfig, RoleMeter, RoleOutcome,
    RolePrompt, RoleRun, RoleSet, RoleStatus, RoleUsage,
};
pub use runtime::{
    ApiKey, ChatClient, ChatError, ChatRequest, ChatStream, HttpErrorBody, OverflowHint,
    ReasoningReplay, ReconnectPolicy, ToolSpec,
};
pub use session::{
    conversation_of, run_session_turn, CompressionSignal, HistoryStore, SessionConfig,
    SessionError, SessionTurn,
};
pub use settings::{
    AssistantModel, AssistantSettings, ChatSettings, ConversationSettings, SkillSelection,
    ToolSwitches, DOC_VERSION,
};
pub use skills::{ContainerMount, SkillMeta, SkillRegistry};
pub use source::SourceTag;
pub use speech::{AsrEngine, SpeechConfig, TtsEngine};
pub use sse::{SseError, SseFrame, SseReader};
pub use stream::{Completion, FinishReason, StreamEvent, StreamParseError, StreamParser, ToolCall};
pub use tokens::{estimate_tokens, format_k, Calibration, Class, Estimate, Estimator, Level};
pub use tools::{Tool, ToolContext, ToolOutcome, ToolRegistry};
pub use transport::{
    HttpRequest, HttpResponse, Method, PlainHttpTransport, ResponseBody, Transport, TransportError,
    Url,
};
pub use workspace::{ReadOptions, ReadResult, Workspace, WorkspaceError};

/// 客户端对外名称。所有导出给 UI / FFI 的版本串都用它。
pub const PRODUCT_NAME: &str = "NMoi-Agent";

/// 与 `Cargo.toml` 同步的 crate 版本。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
