# guyii 输入法 — 设计决策

> 基于 osfans/trime fork，目标设备 **Unihertz Titan 2 Lite**（Android 16，1080×1200）。
> 所有硬件结论来自 KeyProbe 实测（`~/code/titan2-lite-ime/keyprobe-export-*.json`），非推测。

## 一、定位

一个**整合安装包**：输入法本体 + 雾凇词库 + 预编译产物全在一个 APK 里。
装完打开即可打字，**无导入、无向导、无手动部署**。

## 二、硬件实测结论

### 键盘只有字母
`TitanKey` 的 KeyCharacterMap **只包含**：`A-Z`、`0`、`COMMA`、`PERIOD`、`SPACE`、`ENTER`。

**完全没有**：方向键、Ctrl、Esc、Tab、Home/End/PgUp/PgDn。

### Alt 层（印在键帽，与 KCM 完全一致）
```
Q0 W1 E2 R3 T( Y) U_ I- O+ P@
A* S4 D5 F6 G/ H: J# K' L"
Z7 X8 C9 V? B! N, M.
```
数字 0-9 齐全。

### 键盘上打不出的 ASCII 符号（14 个）
```
`  ~  $  %  ^  &  =  [  ]  {  }  \  |  ;
```

### Shift+Alt 是空层
KCM 中字母键的 `alt_shift` 为空 → `Shift+Alt+字母` 系统不产字符，
但输入法仍收到 keyCode + metaState`SHIFT+ALT`。**26 个可自由绑定的组合**。

### Fn 键不可用
Fn 上报 keyCode `403`，但**不设置任何 metaState**，Fn+任意键 = 基础层字符。
部分组合（Fn+E/T/G/C/B/M）被固件吞掉，连事件都不产生。**放弃 Fn 层**。

### 触摸板可用（飞字的前提）
`touchPad`(id=6) = `SOURCE_TOUCHPAD`，完整二维绝对坐标：
`X 0-1079`、`Y 0-748.8`，带 PRESSURE / TOUCH_MAJOR，约 60Hz。

- 输入法经 `dispatchGenericMotionEvent` **能收到**
- **前提：必须开启系统的 Scroll assistant，并把本输入法设为 Sliding Mode 2**
  （Settings → Keyboard gesture → Scroll assistant → 应用列表里找到本输入法）。
  该设置是**按应用**配置的，这一点当初被忽略了 —— 早期以为"必须关闭助手"，
  依据是 KeyProbe 在助手关闭时收到 78 条、开启时只有 3 条（`keyCode 404` 脉冲）。
  那组对比没有区分按应用的模式，结论因此是错的：真正决定事件形态的是
  **该应用被指派了哪个模式**，而非助手的总开关
- 同一页里系统自带的 **Flick typing 开关与本项目无关，保持关闭** ——
  它的说明写明「only supported by the built-in Kika keyboard」
- 纵向滑动时 X 稳定在键宽内（实测 890→O 键、314→E 键），**足以定位到具体键**
- 键宽换算：`X / 108` = 第一排键序（Q=0…P=9）；行高 `Y / 187`

## 三、屏幕两行（Passport 式，本项目重点）

Passport 的物理键盘无任何符号，故需屏幕行补全。
**Titan 2 Lite 不同**：Alt 层已有大部分符号，所以这两行的价值是
**补 PC 键 + 补键盘打不出的符号**，而非重复 Alt 层。

布局自上而下：

```
候选行   （仅打拼音时出现）  [候选1][候选2][候选3]…
功能行   （常驻，▸ 翻页）
  第1页  [Esc][Tab][Ctrl][←][→][↑][↓][⇱][⇲][▸]   ← 默认
  第2页  [=][|][$][~][[][]][{][}][▸]              ← 高频缺失符号
  第3页  [`][\][;][^][&][%][▸]                    ← 低频缺失符号
```

- 候选行在**上**，功能行在**下**（贴近物理键盘，手指移动最短）
- 数字行不做（Alt 层已全有）
- 中文标点不进屏幕行（走 Shift+Alt 层）

屏幕上的 Ctrl 通过 `mergeLatchedModifiers()` 作用于**物理按键**（见下）；
真机行为待验。

## 四、Shift+Alt 标点层

利用空层实现"中文输入中混打英文标点"，**不切模式**：

| 组合 | 输出 | 组合 | 输出 |
|---|---|---|---|
| `Alt+N` | `，` | `Shift+Alt+N` | `,` |
| `Alt+M` | `。` | `Shift+Alt+M` | `.` |
| `Alt+H` | `：` | `Shift+Alt+H` | `:` |
| `Alt+V` | `？` | `Shift+Alt+V` | `?` |
| `Alt+B` | `！` | `Shift+Alt+B` | `!` |
| `Alt+K` | `'` | `Shift+Alt+K` | `'` |
| `Alt+L` | `"` | `Shift+Alt+L` | `"` |

中文模式下 `Alt+键` 经 Rime punctuator 得中文标点；加 `Shift` 强制英文。

## 五、中英文切换

雾凇自带 `ascii_mode` 开关（状态 中/Ａ），绑 **`Shift+Space`**。

## 六、不联网

`INTERNET` 与 `ACCESS_NETWORK_STATE` 均以 `tools:node="remove"` 从合并清单中移除，
任何依赖都无法加回。可用 `aapt2 dump badging` 验证。

## 七、许可证

Trime (GPL-3.0) + 雾凇 rime-ice (GPL-3.0) → 本项目**必须**保持 GPL-3.0。
MIT 不可行。应用名/图标/著作权归 guyii，LICENSE 保留，README 注明来源。

## 八、实施进度

### ✅ 1. 预编译整合包（完成）
- 默认存储改 `APP_STORAGE`，向导的「选数据目录」页删除（剩 2 步，均为 Android 强制）
- `DataManager.SCHEMA_LIST_CUSTOM_PATCH` 由硬编码 `luna_pinyin` 改为 `rime_ice`
  —— 不改则雾凇编译了也不生效
- 在 redroid 部署一次，产物（13 个文件 43MB，`rime_ice.table.bin` 29MB）
  收进 `assets/shared/build/`，走 Trime 既有的 `prebuiltDataDir` 路径
- **实测：全新安装、一次部署都不点，直接打出「中文」候选**
- APK 31MB → 51MB

### ✅ 2. 屏幕功能行（完成）
键盘命名为 **`rime_ice`**，与方案同名 —— `smartMatchKeyboard()` 会优先选用同名键盘，
因此它自动成为该方案的默认屏幕键盘，**零代码**。字母由实体键盘负责，屏幕只补功能键。

| 键盘 | 内容 | width |
|---|---|---|
| `rime_ice` | `Esc Tab Ctrl ← → ↑ ↓ 复制 粘贴 ▸` | 10 |
| `guyii_sym1` | `= \| $ ~ [ ] { } ▸` | 11 |
| `guyii_sym2` | `` ` \ ; ^ & % ▸ `` | 14 |

- 三页由 `▸`（`select:` 动作）循环
- `▸` 长按 → `Keyboard_letter`，切回完整字母键盘（无实体键盘时的退路）
- 每个键盘各设 `keyboard_height: 56`；不设会被主题全局 `keyboard_height: 250` 撑满
- 新增 preset_keys：`Tab`、`Control_L`、`guyii_copy`、`guyii_paste`、三个翻页键
  （复制/粘贴自建而非复用 Trime 的 `copy`/`paste`，否则会显示其自带的繁体标签）

**界面文案统一简体**：用 OpenCC `t2s` 转换 `label`/`states`/`name`/`preview`/`hint`
五个字段共 86 处，注释与符号表内容不动；再手工修 14 处台湾用词
（剪下→剪切、贴上→粘贴、选单→菜单、设定→设置、搜寻→搜索、预设→默认、说明→帮助）。

> ⚠️ `Henkan: {toggle: simplification, states: [ 漢字, 汉字 ]}` **必须保持原样** ——
> 这是繁简切换开关，两个状态值故意一繁一简，转换后会变成 `[汉字, 汉字]` 失去指示作用。

**代码改动**：`TrimeInputMethodService.mergeLatchedModifiers()` —— 原本物理按键的修饰键
只取自 `event` 自身（`forwardKeyEvent`），软键盘锁定的 Ctrl 作用不到物理键。
现在把 `KeyboardWindow.currentKeyboard.modifier` 合并进去，并在 UP 时释放锁定。
配套 `KeyboardWindow.invalidateKeys` 回调用于重绘。

### ✅ 3. Shift+Alt 标点层（完成）
`TrimeInputMethodService.commitAsciiPunctuation()` 在 `forwardKeyEvent()` **最前面**拦截：
`isShiftAltOnly(metaState)` 成立且键在 `ASCII_PUNCTUATION_LAYER` 表里，就直接
`commitText()` 英文标点并清空编码，不进 Rime。

- 只认 SHIFT+ALT，排除 CTRL/META/SYM，避免误吃系统快捷键
- 只在 ACTION_DOWN 提交，UP 也返回 true，防止按键"漏"给编辑框
- 共 13 键：`, . : ? ! ' " ( ) / # @ *`（见第四节表格）
- 平层 `Alt+键` 不受影响，仍走 Rime punctuator 出中文标点 —— 这正是"中文输入中混打英文标点"

### ✅ 4. 飞字（完成）
`TouchpadGesture` 收 `SOURCE_TOUCHPAD` 事件，DOWN 记起点、UP 判方向：

| 手势 | 动作 |
|---|---|
| 上滑 | 选中该列对应的候选词（`keyIndex` = 起点 X ÷ 108） |
| 右滑 | 候选翻页 |
| 左滑 | 退格 |
| 下滑 | 保留未用 |

- 位移 < `MIN_TRAVEL`(120) 视为点击，不触发；`|dx|`>`|dy|` 判为横向
- 无编码时（`composingText` 为空）直接忽略，不干扰正常滑动
- 判定逻辑抽成纯函数 `TouchpadGesture.classify()` / `keyIndexOf()`，
  用实测轨迹做单元测试（`TouchpadGestureTest`，8 例全过），无需真机即可回归

### ✅ 5. 出包（完成）
- 自建 4096 位 RSA 签名密钥 `guyii-release.jks`（有效期 30 年），
  配置在 `keystore.properties`（**不要提交，不要外传**）
- `./gradlew :app:assembleRelease -PbuildABI=arm64-v8a`
- 产物 36MB，包名 `net.guyii.ime`（release 无 `.debug` 后缀），
  标签「guyii 的 Titan Lite2 输入法」，`native-code: arm64-v8a`
- `aapt2 dump badging` 确认权限表里**没有 INTERNET**
- R8 开启但 `-dontobfuscate` + `-keep class net.guyii.ime.core.*`，JNI 符号不受影响
- 已验证 43MB 预编译产物全部随包（未被资源压缩剔除）

### ✅ 6. 大键盘顶掉功能行（已修）
真机上看到的是整块 QWERTY 而非功能行。两个原因叠加：

1. `onCreateView()` 在 Rime 就绪**之前**就调 `smartMatchKeyboard()`，
   拿不到 `schemaId`，退化成按 alphabet 猜 —— 实测贴的是 `qwerty`
2. 五个 guyii 键盘都没写 `lock: true`，于是普通文本框走 `evalKeyboard("")`
   的 `else` 分支时返回 `lastLockKeyboardId`（那个 `qwerty`），**再也回不到功能行**

改法：
- 五个 guyii 键盘全部补 `lock: true`
- `onStartInput` 普通文本分支由 `""` 改为 `".default"`，每次聚焦重新匹配 ——
  即使首次因竞态贴错也能自愈

redroid 实测日志（`Switched to keyboard:`）：

| 场景 | 结果 |
|---|---|
| 首次聚焦文本框 | `qwerty` → 0.5s 后自动纠正 `rime_ice` ✅ |
| 数字框 → 文本框 | `guyii_num` → `rime_ice` ✅ |
| 密码框 → 文本框 | `guyii_ascii` → `rime_ice` ✅ |
| ▸ 翻页 | `rime_ice` → `guyii_sym1` → `guyii_sym2` ✅ |

截图确认功能行为 `Esc Tab Ctrl ← → ↑ ↓ 复制 粘贴 ▸`，第 3 页为 `` ` \ ; ^ & % ▸ ``。

### ✅ 7. 应用快捷键被吞（已修）
中文模式下一些应用的快捷键失灵，尤其是**单个字母**做的快捷键
（列表界面 j/k 翻动、游戏 WASD）和应用自定义的组合键。

根因不在 Rime，在 `forwardKeyEvent()`：只要键值不是 `VoidSymbol` 就
`postRimeJob{...}` 并**无条件 `return true`**，把键吃掉。没有文本框聚焦时
（`inputType == TYPE_NULL`）同样如此 —— 而这时压根没有地方可以上屏，
键被送进 Rime 组成了一个看不见的编码，应用永远收不到。

`isNullInputType` 其实早就算出来了，但只用于决定内联预编辑，没人拿它放行。

改法：新增 `passThroughToApp()`，`TYPE_NULL` 且当前无编码时 `forwardKeyEvent()`
直接 `return false`，交回 `super.onKeyDown()`，应用拿到原始按键。

`TYPE_NULL` 也正是终端一类编辑器表示「请发原始按键而不是上屏文本」的方式，
所以透传本来就是它们期望的行为。

redroid 实测（桌面无文本框按 a/b，统计 Rime 收到的 KeyMessage）：

| | 修复前 | 修复后 |
|---|---|---|
| Rime 介入次数 | 2 | **0** |

反向验证未被破坏：文本框内中文输入正常，`preedit=ni hao‸`、16 个候选，
候选行「你好」显示在功能行上方。

> 注意这个缺陷**中英文模式都有**，不是中文独有 —— 英文模式下 Rime 同样吃掉
> keydown，只是它会把字母 `commitText` 上屏，所以在能打字的地方看起来正常。

### ✅ 8. 符号页重做 / 中英指示 / 方案选单（完成）

**符号页扩到四行**，原 `guyii_sym2` 合并进来后删除，三页变两页：

| 行 | 内容 |
|---|---|
| 1 | `/ \ . , : ; - _ @ #` |
| 2 | `( ) [ ] { } < > ' "` |
| 3 | `` = + ~ | $ % ^ & ` * `` |
| 4 | `http://` `https://` 退格 字母 ▸ |

`http://` `https://` 用 `commit:` 整串上屏。`keyboard_height: 190`。

**中英状态常驻显示**：原本的切换提示走 `showAsciiSwitchTips()`，把提示塞进编码区显示 1 秒，
实测**根本看不到**（无编码时编码区不渲染）。改为在功能行首位放 `guyii_cn_en`
（`toggle: ascii_mode` + `states: [中, 英]`），标签随状态变，常驻可见，点击即切换。
功能行由 10 键变 11 键，`width: 9`。

**方案选单**：原 hotkeys 只有 `F4` / `Control+grave` / `Control+Shift+grave`，
Titan 这三个键**一个都没有**，等于切不了。两条新路：
- 长按功能行首键 →（`send: F4`）
- `Shift+Alt+S`（借用空的 Shift+Alt 层，已加进 `switcher/hotkeys`）

### ✅ 9. 飞字（两轮真机诊断后修好）

真机上一直不工作。做了带文件日志的诊断版本（日志走 MediaStore 落在
`Download/guyii-ime-touchpad.txt`，无需存储权限），两轮实测才定位到**两个独立的原因**。

**原因一：事件根本到不了 `onGenericMotionEvent`**

第一轮日志：4 次输入会话（Chrome 约 10s、BlackBerry Hub 约 16s）全部
`motionEvents=0`，连 source 不符的都没有。交叉验证：同样系统设置下 KeyProbe
（普通 Activity）收到 25 条 `dev=touchPad`、`source=0x100008`。
**事件到得了应用，到不了输入法。**

框架路径从头读到尾（`NativePreIme` → `ViewPreIme` → `ImeInputStage` →
`IMM.dispatchInputEvent` → `ImeInputEventReceiver` → `dispatchGenericMotionEvent`）
**没有任何一处按 source 过滤 motion 事件**；`shouldSkipIme()` 只跳过
`SOURCE_CLASS_POINTER`，而触摸板是 `POSITION` 类。**按代码它就该到，但实测不到，
这个矛盾没能解释**（疑为 Unihertz 定制路由）。

第二轮同时测了两条替代路径，结果泾渭分明：

| 路径 | 结果 |
|---|---|
| `AccessibilityService` + `setMotionEventSources(SOURCE_TOUCHPAD)` | **0 条**（服务确已连接） |
| 输入法自己视图的 `setOnGenericMotionListener` | **245 条**，完整 DOWN/MOVE/UP |

于是改挂视图监听（`listenForFlicks()`），无障碍服务整个删除 ——
它不工作，还要用户授一个吓人的权限。监听不消费事件，不影响触摸板原有行为。

**原因二：纵向阈值定得太高**

19 个完整手势里 7 个被识别，**全是 Right**。唯一一次像样的上滑：
```
(682,874) -> (762,763)   dx=80  dy=-111   travel=111  → 被 120 的阈值挡掉
```
键盘表面只有四行高，纵向行程天然远短于横向，不能共用一个阈值。
点击抖动实测 0～29px，故拆成 `MIN_TRAVEL_H = 120` / `MIN_TRAVEL_V = 70`。

> 这两个原因是叠加的：即便事件能到，纵向阈值也会让选词永远不触发 ——
> 而翻页（横向）却是好的。这正是"翻页像是能用、选词完全没反应"的由来。

单元测试改用这 19 条真机轨迹，覆盖必须识别的与必须拒绝的两类。

### ✅ 9b. 飞字手势重做（横滑跳 5 个字）

初版横滑走 `Page_Down`，而 `page_size: 5`，所以**不管划多远都固定跳 5 个**。

规划时以为要把 `page_size` 改成 10 让键列与候选 1:1，动手前查证发现**假设是错的**：
候选行走的是 **Bulk 模式**而非分页（日志实测 `Bulk(total=-1, highlighted=0,
candidatesCount=16)`），`page_size` 根本不影响它。于是 `default.yaml` 不用动，
也省掉了重新生成预编译产物。

**改动一：候选行可横向滚动、且能放下更多**
`CompactCandidateDelegate` 原本 `canScrollHorizontally() = false`，超出的候选只能靠
下拉箭头展开。改为 `flexWrap = NOWRAP` + 可横向滚动；候选数超过一行容量时不再拉伸
（拉伸会占满整行、把可滚动空间挤掉）。实测一行从 7 个变成 **16 个**。

**改动二：横滑改成连续步进**
`TouchpadGesture` 增加滑动过程中的增量上报 `onScroll(steps)`，
每 `STEP_PX = 108`（一个键宽）走一个候选。短划走一格、整排划过走八格，
**同一个手势覆盖"一个个过"和"一次过多个"**，不需要记两套动作 ——
与 Gboard 拖空格移光标是同一个套路。抬手时若已连续步进过，则不再触发离散动作，
避免一次滑动动两次。

> 移动高亮用 **`Down`/`Up`**，不是 `Left`/`Right` —— 真机逐键实测：
> `DOWN` 前进、`UP` 后退，而左右键是在编码里移光标。高亮可越过当前 16 个窗口
> （实测走到 20 仍正常，候选行会跟着重新取窗口）。

**改动三：上滑按位置选词**
原来是 `selectCandidate(keyIndex)`，而 `keyIndex` 只有 0–9、候选却有 16 个，
右半边选不准。改为用 `findChildViewUnder(startX)` **命中候选行上真正画在那里的那个**，
`selectCandidate(idx, global = true)`。键盘表面与候选行等宽，所以"在哪个位置上滑，
就选中正上方那个"。

**改动四：退格从左滑挪到下滑**
左右滑都归为候选导航后，左滑不能再当退格。下滑原本空着，且"上滑选词 / 下滑删除"方向成对。
下滑不要求正在输入中，没编码时就是普通退格。

| 手势 | 动作 |
|---|---|
| 横滑 | 连续移动高亮，每 108px 一个 |
| 上滑 | 确认当前高亮的候选 |
| 下滑 | 退格 |

### 上滑为何不做「瞄准式选词」

初版让上滑选中手指正上方的候选。真机实测被否决：8 次上滑有 **7 次落在
52%–62%**（x = 572~680）的同一小块 —— 键帽表面没有视觉反馈，手指看不见自己在哪，
所以人只会滑在最舒服的位置。而用户实际的操作序列是「先横滑把高亮移到想要的词，
再上滑」，与瞄准式选词直接冲突。

改为上滑提交当前高亮，两个手势形成一套：**横滑选，上滑确认**。
实现上给 Rime 发一个空格即可 —— 那是 Rime 提交高亮候选的方式，
不需要在这边重建下标，也就不可能算错。

配套：候选行截到 10 个（与键列数一致），**窗口跟随高亮滑动**，
否则高亮移出可见范围就成了「提交一个自己看不见的字」；
窗口偏移后点击/长按候选的下标需加 `windowStart`。

### ✅ 10. 零部署靠固定 mtime 钉死（完成）
改 `default.yaml` 后全新安装又开始重建词库（两分多钟）。根因：Rime 用
`__build_info/timestamps` 里记录的**源文件 mtime** 判断产物是否过期，要求精确相等；
而 APK 解压出来的文件 mtime 是安装时间，**每次都不一样**，所以必然重建 ——
之前那次"零部署"能成只是巧合。

`DataManager.stampSyncedFiles()`：解压后把 `shared/` 下所有文件 mtime 钉成固定值
（2024-01-01），产物目录钉成晚 60 秒。这样跨设备、跨安装都可复现。

实测全新安装：**无 `round #`、无 `finished updating schemas`**，
且因 Rime 立即就绪，连此前 0.5 秒的 `qwerty` 闪烁也消失，一次就选中 `rime_ice`。

> ⚠️ 以后**任何** `assets/shared/` 下的改动，都必须在设备上重新部署一次、
> 把 `files/rime/build/` 的产物拷回 `assets/shared/build/`，否则用户首次启动会重建。

### ⛔ 11. 搜狗词库（做过，已撤销）

做完并验证通过后按要求撤回。撤销的是 `cn_dicts/sogou.dict.yaml` 与
`rime_ice.dict.yaml` 里的那行 `import_tables`，产物已按流程重新生成，
`rime_ice.table.bin` 回到 29,067,948 字节（与加入前逐字节一致），APK 回到 36MB。

留档，以后要再加时不必重新摸索：

**来源**：[wuhgit/CustomPinyinDictionary](https://github.com/wuhgit/CustomPinyinDictionary)
—— 搜狗细胞词库等合并去重，以 fcitx5 **二进制**词库发布（149.9 万条）。

**转换链路**：
```
CustomPinyinDictionary_Fcitx.dict      (29MB 二进制)
  └ libime_pinyindict -d               (Arch 的 libime 包自带)
      └ cpd.txt                        (43MB，格式: 词 pin'yin 0)
          └ 去重 + 转换
              └ cn_dicts/sogou.dict.yaml
```

**去重**：按「词形 + 注音」与 8105 / base / ext / others 比对，
**149.9 万 → 100.7 万**（雾凇本身 89.1 万）。不去重的话三分之一是白占体积。

**权重统一为 1**：搜狗侧导出的词频全是 0，没有可用频率信息；且作为补充词库
不应盖过雾凇精调的词序。实测 `babababa` 前两位仍是「八八八八」「爸爸爸爸」，
搜狗独有的「巴巴爸爸」排第 3 —— 纯增量。

**代价**（这是撤销的原因）：`rime_ice.table.bin` 29MB → 57MB，release APK 37MB → 57MB。

## 九、已知问题

- **只装了「雾凇拼音」一个方案**，方案选单里只有它
- `en_dicts/cn_en_sogou.txt` 是 rime-ice 自带的英文词表，与上面撤销的搜狗词库无关
- 打开方案选单时会临时切成整块字母键盘（选单需要字母键导航），按 Esc 后回到功能行
- 向导第 1 步的文案仍是「Enable **Trime**」，品牌串有遗漏

- `InputMethodUtils.checkIsTrimeSelected()` 在用 adb `ime set` 设置默认输入法时判定为 false，
  导致向导第 2 步不出现 NEXT。真机经系统选择器选择是否同样如此待验
- 预编译产物在 x86_64 生成，**arm64 未验**。Rime 的 .bin 为内存映射结构，
  两者同为小端 64 位理论通用；若不兼容 Rime 会自动重编（退化为慢，不会坏）
- 候选行尚未与功能行整合（当前候选仍走 Trime 默认的候选条）
- **飞字需要系统侧配合**：Settings → Keyboard gesture → Scroll assistant →
  把本输入法设为 **Sliding Mode 2**。系统自带的 Flick typing 开关保持关闭（只对内置
  Kika 键盘生效）
- 以下均在 x86_64/redroid 与单元测试中验证，**arm64 真机尚未实测**：
  预编译产物兼容性、Shift+Alt 标点层、飞字、屏幕 Ctrl 作用于物理键
- `TYPE_NULL` 透传的代价：若某个应用错误地把可编辑框报成 `TYPE_NULL`，
  那里将无法输入中文（只能收到原始按键）。终端类应用属于预期行为，非缺陷
