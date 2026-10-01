# FIX 4.4 会话协议引擎

本模块在两个交易端点之间提供可靠的 FIX 4.4 会话层消息传递。引擎同时支持
发起端（INITIATOR）与接受端（ACCEPTOR），底层默认使用进程内回环传输
（`LoopbackLink`），可按方向注入确定性的丢包、重复与乱序故障。

## 1. 消息编码与解码

- 标签值格式：`8=FIX.4.4`（SOH）`9=<bodyLen>`（SOH）消息体（SOH）
  `10=<checksum>`（SOH）。
- `FixEncoder` 自动填充 BeginString(8)、BodyLength(9)、CheckSum(10)。
- CheckSum 是从 `8=` 起到 `10=` 前所有字节之和对 256 取模，固定三位数字。
- `FixDecoder` 在解码阶段依次校验：
  - 帧以 SOH 结尾、字段形如 `tag=value`、标签为正整数、值非空；
  - BeginString 与配置一致；
  - BodyLength 与实际消息体长度一致；
  - CheckSum 与计算值一致；
  - 必填头字段存在且可解析：35(MsgType)、49(SenderCompID)、56(TargetCompID)、
    34(MsgSeqNum)。
- 会话层还会校验 CompID 方向、序号范围、Logon 的 HeartBtInt(108) 等。
- 未知消息类型：
  - 所有 FIX 会话管理类型（0/1/2/3/4/5/A）由引擎处理，无法识别的管理类型一律拒绝；
  - 非管理类型默认全部投递给上层；若 `allowedAppMsgTypes` 非空，则仅允许集合内
    的应用 MsgType，集合外按“未知消息类型”拒绝。

### 拒绝策略（`RejectPolicy`）

| 策略 | 行为 |
| --- | --- |
| `REJECT_AND_CONTINUE`（默认之外可选） | 回 Session Reject(35=3)，会话保持 |
| `REJECT_AND_DISCONNECT`（默认） | 回 Reject，随后发 Logout 并断开 |
| `SILENT_DROP` | 不回应，静默丢弃该帧 |

## 2. 会话状态机

```
                 start() (initiator)
 DISCONNECTED --------------------------> LOGON_SENT
      ^                                      |  收到 Logon(35=A)
      |                                      v
      |             双方交换 Logon         ACTIVE
      |   acceptor 在 DISCONNECTED 收到 Logon -> ACTIVE
      |                                      | logout() / 收到 Logout
      |                                      v
      +-------------------------------- LOGOUT_SENT -> CLOSED
```

- 发起端发送 Logon（含 HeartBtInt，可选 141=Y 重置），收到对端 Logon 后进入 ACTIVE。
- 接受端在 DISCONNECTED 等待 Logon，校验后回 Logon 并进入 ACTIVE。
- 任意一端调用 `logout()`：发 Logout，进入 LOGOUT_SENT，收到对端 Logout 后 CLOSED。
  未发起注销的一端收到 Logout 后回 Logout 并进入 CLOSED。
- 非法状态迁移（如未登录发送应用消息、ACTIVE 下重复 Logon、LOGON_SENT 下收到
  普通应用消息）抛出 `FixException(INVALID_STATE_TRANSITION)`，按拒绝策略处理。

### 心跳与活性探测

- 距上次发送超过 `heartbeatInterval` 时主动发送 Heartbeat(0)。
- 距上次收到数据超过 `heartbeatInterval` 时发送 TestRequest(1)。
- 在 `testRequestTimeout` 内未收到带对应 TestReqID(112) 的 Heartbeat，会话判定
  对端失活并断开（进入 DISCONNECTED）。

## 3. 序列号与可靠传递

- 发送序号在“帧落盘之后”单调分配（1 起）；序号一旦分配即持久化，绝不重用
  （协商重置除外）。
- 接收端维护 `nextTargetSeq`：
  - `seq == nextTargetSeq`：顺序处理，推进序号；
  - `seq < nextTargetSeq`：视为重复，应用消息绝不二次投递；管理消息按需幂等处理；
  - `seq > nextTargetSeq`：检测到缺口，暂存超前消息并发送 ResendRequest
    (35=2, 7=begin, 16=end, 0 表示持续到最新)。
- 收到 ResendRequest 的一端按请求补齐：
  - 应用消息以原序号重发，带 PossDupFlag(43)=Y 与 PossResend(97)=Y；
  - 管理消息不能重放，对其覆盖的序号范围发送 GapFill 形式的 SequenceReset
    (123=Y, 36=NewSeqNo)，保证对端序号连续。
- 重传请求按 `resendTimeout` 周期重试，最多 `maxResendAttempts` 次，仍未补齐则
  断开并给出 `GAP_TOO_LARGE` 错误。
- 缺口补齐期间到达的新消息进入有序暂存区，按序号依次放行，不错序、不丢消息。

## 4. 序列号重置

有两种重置方式：

1. **登录重置（ResetSeqNumFlag）**：Logon 带 141=Y 且 MsgSeqNum=1，双方将本地
   收发序号重置为 1，清空重发日志。序号不是 1 的重置 Logon 被拒绝。
2. **在线协商重置（显式 SequenceReset）**：
   - 发起方发送 `35=4`（不带 123）并携带协商的 NewSeqNo(36)，本地进入
     “等待确认”状态；
   - 对端收到相同 NewSeqNo 的 SequenceReset 后视为双方确认；
   - 重置期间到达的普通消息进入暂存区，重置完成后按新序号继续放行；
   - 双方提出的 NewSeqNo 不一致或确认序号异常时拒绝并给出明确错误。

## 5. 持久化与崩溃恢复

存储根目录由 `storagePath` 配置（为空时使用不落盘的内存存储，仅用于测试）。
每个会话在 `<storagePath>/<FIX版本>_<sender>_<target>/` 下维护：

| 文件 | 内容 |
| --- | --- |
| `session.id` | 固定的 BeginString / SenderCompID / TargetCompID，重开时校验 |
| `outgoing.log` | 长度前缀编码的已发送帧（含管理消息），用于重传 |
| `state.dat` | `nextSenderSeq`、`nextTargetSeq`、已投递的最高入站序号，原子写入 |

恢复语义：

- 状态文件通过“临时文件 + fsync + 原子 rename”更新；崩溃只会留下尚未被对端
  看到的额外发送帧，不会造成已持久化序号出现空洞。
- 重启后发起端从持久化的 `nextSenderSeq` 继续发 Logon；若接受端发现 Logon 序号
  超前，会通过 ResendRequest 要求从缺口重放，重放帧来自 `outgoing.log`。
- 已确认/已投递的入站消息不会二次投递给上层（序号去重）。
- 以下情况拒绝自动继续并抛出 `UNRECOVERABLE_STORE`：状态文件损坏、发送日志缺帧、
  身份文件与当前会话不一致、序号不可能（小于 1）。此时需要人工介入，引擎不会
  猜测恢复位置。

## 6. 并发、有界队列与确定性行为

- 接收线程只负责解码、校验与状态机处理；发送线程独占序号分配与物理写。
  上层调用 `send()` 仅入队，慢消费者/慢网络不会阻塞接收链路。
- 待发送队列容量由 `sendQueueCapacity` 限制（默认 1024）。队列满时 `send()`
  **立即**抛出 `OutboundRejectedException`（非阻塞、不丢已入队消息），调用方
  可据此做背压或放弃；行为确定。
- 管理消息（Logon/Logout/Heartbeat/TestRequest/ResendRequest/SequenceReset/
  Reject 以及重传帧）相对普通应用消息优先出队，保证控制面不被数据面阻塞。
- 时间来自可替换的 `Clock`；测试中使用脚本时钟，同一配置、同一输入与同一故障
  计划产生确定的消息序列。

## 7. 配置项（`SessionConfig`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `beginString` | `FIX.4.4` | 协议版本，编解码强制校验 |
| `heartbeatInterval` | 30s | 心跳发送与活性检测周期 |
| `testRequestTimeout` | 10s | TestRequest 无应答的断链超时 |
| `resendTimeout` | 5s | ResendRequest 重试间隔 |
| `maxResendAttempts` | 3 | 最大重传请求次数，超限断开 |
| `sendQueueCapacity` | 1024 | 待发送队列上限，满则抛 `OutboundRejectedException` |
| `rejectPolicy` | `REJECT_AND_DISCONNECT` | 非法消息的拒绝策略 |
| `storagePath` | 空（内存存储） | 持久化目录根路径 |
| `resetOnLogon` | false | 发起/接受 Logon 时是否接受/携带 141=Y |
| `allowedAppMsgTypes` | 空（全部放行） | 允许投递给上层的应用 MsgType 白名单 |

## 8. 主要类型

- `fix.FixEncoder` / `fix.FixDecoder`：标签值编解码与结构校验。
- `fix.FileMessageStore` / `session.InMemoryMessageStore`：持久化/内存存储。
- `transport.LoopbackLink` / `LoopbackTransport` / `FaultPlan`：回环链路与故障注入。
- `session.FixSession`：会话引擎（状态机、序号、重传、重置、心跳、恢复）。
- `session.FixApplication`：上层回调（onLogon/onLogout/onMessage）。

## 9. 自动化测试

`src/test` 覆盖：

- `CodecTest`：编解码往返、校验和/必填字段/格式错误；
- `GapResendTest`：丢包后的重传请求与按序补齐；
- `DuplicateTest`：重复帧仅投递一次；
- `ReorderTest`：相邻帧乱序后的按序交付；
- `ReconnectTest`：正常注销后以持久化序号重连；
- `SequenceResetTest`：在线协商重置后的一致序号续传；
- `CrashRecoveryTest`：模拟崩溃后从最近一致位置恢复、补齐缺口；损坏存储被拒绝；
- `HeartbeatTimeoutTest`：对端静默时 TestRequest 超时断链；
- `SendQueueTest`：慢消费者下队列满的确定拒绝；
- `IllegalTransitionTest`：非法状态迁移被拒绝。
