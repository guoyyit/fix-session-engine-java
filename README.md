# FIX 4.4 Session Engine (Java)

一个在两个交易端点之间提供可靠会话层消息传递的 FIX 4.4 会话协议引擎。
同时支持发起端（Initiator）与接受端（Acceptor），传输层使用进程内本地回环，
可在网络层注入丢包、重复与乱序以验证可靠性。

核心代码位于 `src/main/java/com/github/guoyyit/fixsessionenginejava/fix/`。

## 能力总览

- 标签/值消息编解码：自动生成 `BodyLength(9)` 与 `CheckSum(10)`，校验
  BeginString、字段合法性、必填字段、长度与校验和。
- 完整会话状态机：登录、注销、心跳、测试请求、断线恢复，非法状态迁移被拒绝。
- 基于序列号的可靠传递：发送序号单调递增；接收方发现缺口时发起
  ResendRequest(2)，对端用 PossDup 重放补齐，管理消息以 GapFill(4) 跳过；
  重复消息不会再次投递给上层。
- 双方确认的序列号重置（ResetSeqNum=1），重置过程中的消息不丢不乱序，
  重置后双方从一致序号继续。
- 消息与会话状态持久化：崩溃重启后从最近一致位置恢复；无法恢复时拒绝自动
  继续并抛出明确错误。
- 发送与接收解耦：有界待发队列，慢消费者不阻塞发送链路；队列与重传次数
  均有上限，超限行为确定。
- 心跳间隔、重传超时、最大重传次数、存储路径等全部可配置。

## 消息编码

`FixMessage` 是有序的标签/值集合（不含 8/9/10 三个信封标签）。

```java
FixMessage msg = new FixMessage()
        .addField(FixTags.MSG_TYPE, "D")
        .addField(58, "hello");
String raw = msg.encode("FIX.4.4");   // 自动补 8=FIX.4.4、9=BodyLength、10=CheckSum
FixMessage decoded = FixMessage.decode(raw);
```

解码时会拒绝（抛出 `FixParseException`，携带 RefTagID 与 SessionRejectReason）：

- 非数字/非法标签号、字段格式错误、空值、重复标签；
- 缺失必填标签（至少要求 `MsgType(35)`）；
- BodyLength 不匹配；
- CheckSum 错误（该校验和错误会直接断开，因为原始字节流已不可信）；
- 消息未以 SOH 结尾或字段顺序缺少信封。

未知的应用消息类型由 `RejectPolicy` 决定如何处理（见“拒绝策略”）。

## 会话状态机

`SessionState` 定义以下状态，所有迁移都经过 `transition()` 的白名单校验，
非法迁移抛出 `FixSessionException.IllegalTransition`：

```
DISCONNECTED
   ├─(initiator start)─► LOGON_SENT
   └─(acceptor start)──► LOGON_RECEIVED
LOGON_SENT / LOGON_RECEIVED
        └── Logon(A) 交换 ──► RESYNC ──(缺口补齐)──► ACTIVE
ACTIVE
   ├─ 我方发起重置 ─► RESET_REQUEST_SENT ─► RESET_PENDING_PEER ─► ACTIVE
   ├─ 对端发起重置 ─► RESET_REQUEST_RECEIVED ─► RESET_PENDING_PEER ─► ACTIVE
   ├─ Logout ─► LOGOUT_SENT ─► DISCONNECTED
   └─ 对端 Logout ─► LOGOUT_RECEIVED ─► DISCONNECTED
```

### 登录

发起端发送 `Logon(A, 108=HeartBtInt)`，接受端校验后回 Logon。干净登录后双方
进入 ACTIVE。若检测到对端 Logon 序号形成缺口，进入 RESYNC 并通过
ResendRequest/重放/GapFill 补齐后才 ACTIVE。

### 心跳与活性探测

每隔 `heartBtInt` 有一个定时检查：

- 距上次发送超过一个间隔：发送 `Heartbeat(0)`；
- 距上次接收超过一个间隔且无待确认探测：发送 `TestRequest(1, 112=TestReqID)`；
- TestRequest 必须在约两个心跳间隔内收到对端 Heartbeat（回显 TestReqID），
  否则判定对端失活并断开。

### 注销

`logout()` 发送 `Logout(5)` 并等待对端确认；超时则直接断开。任一端收到
Logout 后回 Logout 并关闭传输。

### 拒绝策略 `RejectPolicy`

- `REJECT`（默认）：回 `Reject(3)`（携带 RefSeqNum/RefTagID/RefMsgType/原因），
  会话保持；
- `IGNORE`：丢弃非法消息，仅通过 `Application.onError` 通知本端；
- `DISCONNECT`：发送带原因的 Logout 并终止连接。

校验和错误不受该策略控制，一律断开。

## 序列号可靠传递

- 发送序号 `nextSenderSeqNum` 由存储单调分配，消息先落盘再发送。
- 接收序号 `nextTargetSeqNum` 在消息按序处理后推进。
- 收到 `seqNum > nextTarget`：放入乱序缓冲并发送
  `ResendRequest(7=begin,16=0)`，启动重传超时定时器。
- 对端处理 ResendRequest：可重放的应用消息用 `PossDupFlag=Y` 重放，
  Heartbeat/TestRequest/ResendRequest/Reject/Logon/Logout 等管理消息以及
  Logon 用 `SequenceReset(4, GapFillFlag=Y, NewSeqNo)` 跳过。
- 重传未在 `resendTimeout` 内补齐则重试，最多 `maxResendAttempts` 次，
  超过后断开（确定性失败，不无界挂起）。
- 重复的应用消息（已处理序号）不再次投递上层；重放填补真实缺口的消息
  恰好投递一次。

## 序列号重置

`resetSequenceNumbers()` 执行双方确认的重置，期间不接收/发送普通应用消息，
保证重置窗口内的消息不丢不乱序。协议为四路握手（ZZ01/ZZ02 为引擎内部管理类型）：

```
发起方 ── ZZ01 (旧 epoch 序号) ──► 接受方
发起方 ◄── ZZ02 (旧 epoch 序号) ── 接受方
发起方 ── Heartbeat seq=1 (新 epoch 探测) ──► 接受方
发起方 ◄── Heartbeat seq=1 / seq=1,PossDup (确认) ── 接受方
```

双方同时发起时，Acceptor 让步（转为接收方角色），避免死锁。探测帧不计入
新会话序号；双方确认落盘后才清除“重置挂起”标记并回到 ACTIVE，从序号 1 继续。

## 持久化与崩溃恢复

`FileMessageStore` 在每个会话目录下维护：

- `state.nv`：`nextSenderSeqNum`、`nextTargetSeqNum`、`resetPending`，
  采用临时文件 + `fsync` + 原子 rename；
- `outgoing.log` / `incoming.log`：只追加的 hex 编码记录 `<seq>|<hex raw>`，
  每条记录 `fsync` 后再推进状态指针。

恢复语义：

- 启动时校验 1..nextSender-1 的发送日志必须连续无缺口，否则抛出
  `FixSessionException.Unrecoverable`，拒绝自动继续；
- 状态指针之后的残留记录视为崩溃前未落指针的陈旧数据，裁剪后继续；
- 仅有日志而状态文件缺失/损坏，直接判定不可恢复；
- 若崩溃发生在“本地已重置但对端尚未确认”的窗口（`resetPending=true`），
  重启时拒绝继续并给出明确错误，避免双方序号视图分叉。

已确认的消息不会重复投递，未确认的消息可从发送日志重放，不会跳过。

## 并发与资源上限

- 协议状态只在单一协议执行器（`TaskRunner`）上串行访问；回环传输由短周期
  poll 拉取，避免慢消费者阻塞发送。
- 应用发送 `send()` 只把消息放入有界 `LinkedBlockingQueue`（容量
  `maxOutboundQueueSize`），随后由协议线程泵出。
- 队列满时 `send()` 立即抛出 `FixSessionException.QueueOverflow`，消息不会
  被分配序号，调用方可稍后重试。这是确定的背压行为，不阻塞、不静默丢弃。
- 重传次数上限为 `maxResendAttempts`，超限后会话断开。

## 配置项 `SessionConfig`

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `beginString` | `FIX.4.4` | FIX 协议版本 |
| `heartBtInt` | `30s` | 心跳间隔/活性探测基准 |
| `resendTimeout` | `5s` | 缺口重传请求超时 |
| `maxResendAttempts` | `3` | 最大重传次数，超限断开；也用于重置握手重试 |
| `resetTimeout` | `10s` | 序列号重置握手超时 |
| `maxOutboundQueueSize` | `10000` | 待发应用消息队列上限 |
| `storagePath` | `target/fix-sessions` | 持久化根目录（按 CompID 分子目录） |
| `rejectPolicy` | `REJECT` | 非法/未知消息的处理策略 |
| `applicationMsgType(...)` | 空 | 允许投递给上层的应用 MsgType 白名单 |

同一配置、相同输入在确定性测试设施（`VirtualClock` + `ManualTaskRunner`）下
产生确定的消息序列。

## 本地回环与故障注入

`LoopbackTransport.pair()` 创建互相连接的两个端点。通过
`setFaultInjector(...)` 可注入：

- `LoopbackTransport.Faults.drop(predicate)`：丢包；
- `LoopbackTransport.Faults.duplicate(predicate)`：重复；
- `LoopbackTransport.Faults.reorderNextTwo()`：相邻两条乱序；
- 也可自定义 `FaultInjector`，用 `Delivery.now/defer/releaseAll` 控制命运。

## 自动化测试

`src/test/java/.../tests/`：

- `FixMessageCodecTest`：BodyLength/CheckSum、非法字段、缺失必填、重复标签等；
- `SessionLogonTest`：登录建立、应用消息有序投递、非法状态发送；
- `ReliabilityTest`：丢包重传、重复去重、乱序缓冲、队列溢出、心跳保活、
  未知消息类型拒绝；
- `SequenceResetTest`：双方确认重置、重置后从 1 开始不丢不乱序、
  Acceptor 发起重置；
- `RecoveryTest`：断线重连序号连续、崩溃后消息不重复、状态文件缺失/重置挂起
  时拒绝自动恢复。

运行：

```bash
mvn test
```
