//! 能力门控（用户策略：可选增强默认关；助手必须绑定工作区才能用相关功能）。
//!
//! 为什么把它做成代码而不是文档约定：默认关只有落到一个**可测试的判定函数**上才靠得住。
//! 所有工具入口都应该先问一次 [`AssistantPolicy::allows`]，而不是各自判断"有没有开"。
//!
//! 两层门，缺一不可：
//!
//! 1. **绑定**：没绑工作区 → 文件工具、命令、容器一律不可用（连只读都不给）。
//! 2. **开关**：绑了工作区也不自动拥有能力，容器 / 原生命令 / 网络 / 凭据代理各自默认关。
//!
//! 这样"最坏情况"永远发生在用户显式打开之后，而不是默认就暴露。

use serde::{Deserialize, Serialize};

/// 一项具体能力。粒度按"用户能理解的开关"来切，不按代码结构切。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Capability {
    /// 读工作区文件。
    WorkspaceRead,
    /// 写 / 编辑工作区文件。
    WorkspaceWrite,
    /// 目录、查找、搜索。
    WorkspaceSearch,
    /// 在容器里执行命令（Android: PRoot；Linux: podman/nspawn/bwrap）。
    ContainerExec,
    /// 在宿主机直接执行命令（Linux / Windows 的原生命令增强）。
    NativeShell,
    /// 访问网络（容器或工具需要联网时）。
    Network,
    /// 向凭据代理请求凭据（Android 的透明代理 / helper）。
    SecretsBroker,
}

impl Capability {
    pub const ALL: [Capability; 7] = [
        Capability::WorkspaceRead,
        Capability::WorkspaceWrite,
        Capability::WorkspaceSearch,
        Capability::ContainerExec,
        Capability::NativeShell,
        Capability::Network,
        Capability::SecretsBroker,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Capability::WorkspaceRead => "workspace_read",
            Capability::WorkspaceWrite => "workspace_write",
            Capability::WorkspaceSearch => "workspace_search",
            Capability::ContainerExec => "container_exec",
            Capability::NativeShell => "native_shell",
            Capability::Network => "network",
            Capability::SecretsBroker => "secrets_broker",
        }
    }

    /// 是否属于"工作区类"能力（必须先绑定工作区）。
    pub fn needs_workspace(self) -> bool {
        matches!(
            self,
            Capability::WorkspaceRead
                | Capability::WorkspaceWrite
                | Capability::WorkspaceSearch
                | Capability::ContainerExec
                | Capability::NativeShell
        )
    }
}

/// 助手与工作区的绑定。绑定本身只确定"根在哪、能不能写"，不授予增强能力。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceBinding {
    pub workspace_id: String,
    /// 只读绑定：写与编辑一律拒绝（默认就是只读，写要单独开）。
    pub read_only: bool,
}

impl WorkspaceBinding {
    pub fn read_only(workspace_id: impl Into<String>) -> Self {
        Self {
            workspace_id: workspace_id.into(),
            read_only: true,
        }
    }

    pub fn read_write(workspace_id: impl Into<String>) -> Self {
        Self {
            workspace_id: workspace_id.into(),
            read_only: false,
        }
    }
}

/// 拒绝原因。UI 要能把它翻译成人话，而不是只给一个灰掉的按钮。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DenyReason {
    /// 助手还没绑工作区。
    NoWorkspace,
    /// 工作区是只读绑定。
    ReadOnlyWorkspace,
    /// 该增强没有开启（默认就是关的）。
    FeatureDisabled,
}

impl DenyReason {
    pub fn as_str(self) -> &'static str {
        match self {
            DenyReason::NoWorkspace => "no_workspace",
            DenyReason::ReadOnlyWorkspace => "read_only_workspace",
            DenyReason::FeatureDisabled => "feature_disabled",
        }
    }

    /// 给用户看的一句话。
    pub fn explain(self) -> &'static str {
        match self {
            DenyReason::NoWorkspace => "这个助手还没绑定工作区，文件与命令功能不可用",
            DenyReason::ReadOnlyWorkspace => "工作区是只读绑定，写入与编辑不可用",
            DenyReason::FeatureDisabled => "该增强默认关闭，需要时在设置里手动开启",
        }
    }
}

/// 一个助手（或一次会话）的能力策略。
///
/// 默认值就是 `Default`：什么都没绑、什么增强都没开。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssistantPolicy {
    pub binding: Option<WorkspaceBinding>,
    /// 容器执行：默认关（Android / Linux 都是可选增强）。
    pub container_enabled: bool,
    /// 宿主机原生命令：默认关（Linux / Windows）。
    pub native_shell_enabled: bool,
    /// 容器 / 工具联网：默认关。
    pub network_enabled: bool,
    /// 凭据代理：默认关（Android 透明代理）。
    pub broker_enabled: bool,
}

impl AssistantPolicy {
    /// 什么都没开。这是新建助手 / 新会话的默认状态。
    pub fn locked_down() -> Self {
        Self::default()
    }

    /// 绑定工作区，其余增强保持关闭。
    pub fn with_binding(mut self, binding: WorkspaceBinding) -> Self {
        self.binding = Some(binding);
        self
    }

    pub fn enable_container(mut self, enabled: bool) -> Self {
        self.container_enabled = enabled;
        self
    }

    pub fn enable_native_shell(mut self, enabled: bool) -> Self {
        self.native_shell_enabled = enabled;
        self
    }

    pub fn enable_network(mut self, enabled: bool) -> Self {
        self.network_enabled = enabled;
        self
    }

    pub fn enable_broker(mut self, enabled: bool) -> Self {
        self.broker_enabled = enabled;
        self
    }

    pub fn workspace_id(&self) -> Option<&str> {
        self.binding.as_ref().map(|b| b.workspace_id.as_str())
    }

    /// 单个能力的判定。顺序：先看绑定，再看开关——绑定缺失不该被"开关已开"糊过去。
    pub fn check(&self, capability: Capability) -> Result<(), DenyReason> {
        if capability.needs_workspace() && self.binding.is_none() {
            return Err(DenyReason::NoWorkspace);
        }

        match capability {
            Capability::WorkspaceRead | Capability::WorkspaceSearch => Ok(()),
            Capability::WorkspaceWrite => match &self.binding {
                Some(binding) if binding.read_only => Err(DenyReason::ReadOnlyWorkspace),
                Some(_) => Ok(()),
                None => Err(DenyReason::NoWorkspace),
            },
            Capability::ContainerExec => {
                if self.container_enabled {
                    Ok(())
                } else {
                    Err(DenyReason::FeatureDisabled)
                }
            }
            Capability::NativeShell => {
                if self.native_shell_enabled {
                    Ok(())
                } else {
                    Err(DenyReason::FeatureDisabled)
                }
            }
            Capability::Network => {
                if self.network_enabled {
                    Ok(())
                } else {
                    Err(DenyReason::FeatureDisabled)
                }
            }
            Capability::SecretsBroker => {
                if self.broker_enabled {
                    Ok(())
                } else {
                    Err(DenyReason::FeatureDisabled)
                }
            }
        }
    }

    pub fn allows(&self, capability: Capability) -> bool {
        self.check(capability).is_ok()
    }

    /// 当前可用的能力清单。UI 用它渲染"哪些按钮该亮"。
    pub fn allowed(&self) -> Vec<Capability> {
        Capability::ALL
            .iter()
            .copied()
            .filter(|capability| self.allows(*capability))
            .collect()
    }

    /// 容器要联网时，两个开关都得开。
    pub fn allows_container_with_network(&self) -> bool {
        self.allows(Capability::ContainerExec) && self.allows(Capability::Network)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_policy_denies_everything() {
        let policy = AssistantPolicy::locked_down();
        for capability in Capability::ALL {
            assert!(!policy.allows(capability), "{capability:?} 不该默认可用");
        }
        assert!(policy.allowed().is_empty());
        assert_eq!(
            policy.check(Capability::WorkspaceRead),
            Err(DenyReason::NoWorkspace)
        );
    }

    #[test]
    fn binding_alone_unlocks_only_read_and_search() {
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        assert!(policy.allows(Capability::WorkspaceRead));
        assert!(policy.allows(Capability::WorkspaceSearch));
        assert_eq!(
            policy.check(Capability::WorkspaceWrite),
            Err(DenyReason::ReadOnlyWorkspace)
        );
        // 绑了工作区也不等于有容器 / 命令 / 网络 / 代理
        assert_eq!(
            policy.check(Capability::ContainerExec),
            Err(DenyReason::FeatureDisabled)
        );
        assert_eq!(
            policy.check(Capability::NativeShell),
            Err(DenyReason::FeatureDisabled)
        );
        assert_eq!(
            policy.check(Capability::Network),
            Err(DenyReason::FeatureDisabled)
        );
        assert_eq!(
            policy.check(Capability::SecretsBroker),
            Err(DenyReason::FeatureDisabled)
        );
        assert_eq!(policy.workspace_id(), Some("ws-1"));
    }

    #[test]
    fn read_write_binding_allows_editing() {
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        assert!(policy.allows(Capability::WorkspaceWrite));
    }

    #[test]
    fn enabling_container_requires_binding_too() {
        // 开关开了但没绑工作区：仍然拒绝。这条是最容易被写错的地方。
        let policy = AssistantPolicy::locked_down().enable_container(true);
        assert_eq!(
            policy.check(Capability::ContainerExec),
            Err(DenyReason::NoWorkspace)
        );

        let policy = policy.with_binding(WorkspaceBinding::read_only("ws-1"));
        assert!(policy.allows(Capability::ContainerExec));
    }

    #[test]
    fn container_network_needs_both_switches() {
        let base = AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        assert!(!base
            .clone()
            .enable_container(true)
            .allows_container_with_network());
        assert!(!base
            .clone()
            .enable_network(true)
            .allows_container_with_network());
        assert!(base
            .enable_container(true)
            .enable_network(true)
            .allows_container_with_network());
    }

    #[test]
    fn deny_reasons_have_human_copy() {
        for reason in [
            DenyReason::NoWorkspace,
            DenyReason::ReadOnlyWorkspace,
            DenyReason::FeatureDisabled,
        ] {
            assert!(!reason.explain().is_empty());
            assert!(!reason.as_str().is_empty());
        }
    }

    #[test]
    fn capability_names_are_stable() {
        for capability in Capability::ALL {
            assert_eq!(capability.as_str(), capability.as_str());
        }
        assert!(Capability::ContainerExec.needs_workspace());
        assert!(!Capability::Network.needs_workspace());
        assert!(!Capability::SecretsBroker.needs_workspace());
    }
}
