# Emby/Jellyfin 有声书（书模式）与断流重连设计文档

> 适用版本：对齐上游 `cromaguy/Rhythm` 5.5.480.1260 之后（fork 分支 `feat/emby-book-recovery`）。

## 1. 需求

1. 程序应能读取 Emby/Jellyfin 服务器上的**书籍（AudioBook / Book）类型的专辑或曲目**，并将其切入「书模式」播放。
2. 书模式：
   - 没有随机（shuffle）模式 —— 播放顺序严格按集数（章节）顺序；
   - 集数顺序 = 服务端 `ParentIndexNumber → IndexNumber → 标题`（见 `AudiobookChapterOrder`）；
   - 续播：以账号在 Emby 侧保存的进度为准（每章 `UserData.PlaybackPositionTicks`），下次打开直接续播到上次集数与位置；
   - 程序结束后再次打开：界面显示「继续播放」卡片，点击后从服务端取进度续播。
3. 断流重连：
   - Emby 播放中网络中断（表现为 `Source error` / 弹窗 "Track Playback Error"）时自动尝试重连 3 次；
   - 重连成功自动续播、不中断用户感知；
   - 3 次均失败：界面显示详细错误（错误码名、HTTP/源原因链、尝试次数、曲目信息），并提供「重试」「导出日志」；
   - 详细日志写入程序日志目录，**总量上限 30 MB**，且日志必须脱敏（`api_key`、Token、密码等）。

## 2. 服务端类型判定

| 标识 | 含义 | 处理 |
| --- | --- | --- |
| `Type == "AudioBook"` | Jellyfin 4.9+ / Emby 有声书 | 书模式 |
| `Type == "Book"` | Emby 书籍（音频书籍） | 书模式 |
| `Type == "Audio"` / `MusicAlbum` | 普通音乐 | 普通模式 |

判定函数：`StreamingItemType.isBookType(itemType)`（大小写不敏感）。曲目级 `ProviderSong.itemType` 在 `JellyfinApiClient.parseAudioItems` 中从 JSON `Type` 析出；专辑级同理（`parseAlbumItem`）。

## 3. 数据流（书模式）

```
JellyfinApiClient (Fields: UserData, IndexNumber, ParentIndexNumber, Type)
        │ ProviderSong.itemType / parentIndexNumber / userData(PlaybackPositionTicks)
        ▼
StreamingMusicRepositoryImpl.mapProviderSong
        │ StreamingSong.itemType / parentIndexNumber / userData(StreamingUserData)
        ▼
StreamingMusicViewModel.playQueue
        ├─ 检测 isBookQueue → 强制 shuffle=false + AudiobookChapterOrder.sort
        ├─ BookSessionStore.saveSession（本地卡片）
        ▼
MusicViewModel.playQueue (Song.isAudiobook) → controller.play()
        │
        └─ applyStreamingResumePosition → repository.getResumePosition(songId)
                        └─ JellyfinApiClient.getPlaybackPosition (UserData ticks → ms)
```

**续播定位**：`BookResumeSelector.select(chapters)` 纯函数从每章 `userData` 中挑选「最后一个未完成（<95%）且位置 > 0」的章节；找不到则回退容器级 `getPlaybackPosition(bookId)`；仍无则从头播放（index 0 / 0ms）。单集书籍（container 携带整本书进度）自然落入回退分支。

**「继续播放」卡片**：`BookSessionStore`（SharedPreferences JSON，最多 10 条）在书模式开始/章节切换时写入 `bookId/title/author/artworkUrl/chapterId/chapterIndex/positionMs`；streaming 首页 (`HomeScreen.ContinueListeningBookCard`) 展示最新一条；点击调 `StreamingMusicViewModel.resumeBook(session)` —— 重新取章节 + `getBookResumeTarget` → `playQueue(chapters, index, shuffle=false)`。

## 4. 断流重连（StreamingRecoveryController）

触发：`MediaPlaybackService.onPlayerError`（仅当 `currentMediaItem.mediaId` 是 `streaming://` 或 `SERVICE::id`）。本地曲目不经过恢复控制器。

```
onPlayerError
  ├─ StreamingRecoveryPolicy.isStreamingMediaId?  ──否──▶ handlePlaybackError（原逻辑）
  └─ 是
      └─ handlePlayerError → runRecoveryLoop
           attempt 1..3:
             1) delay(backoff)                      1s / 2s / 4s
             2) repository.invalidateStreamingUrlCache(mediaId)
                repository.getStreamingUrl(mediaId)  → 新 URL
             3) stopPlayer()（若有 crossfade 过渡先 cancelPendingTransition）
             4) 重建 MediaItem（同 mediaId + 新 URI + 原 metadata）
                player.setMediaItem(item, index); prepare();
                seekTo(index, max(0, lastPos - 2s))
             5) play()
          成功 → 回到 IDLE / 失败 3 次 →
             PlaybackErrorCenter.publish(failure)  ← UI 订阅显示详细错误
             AppLogFileManager.logError(...）       ← streaming-errors.log
```

- 重试上限与退避：`StreamingRecoveryPolicy`（超期值取最后一次 4s）。
- 并发保护：恢复期间 `state==ATTEMPTING` 忽略其它 onPlayerError。
- UI 弹窗：`TrackCorruptionDialog` 增加 `errorDetail`（等宽字体可滚动）、`onRetry`（重试按钮）、「导出日志」（`FileProvider` 分享 `filesDir/logs` 最新文件）。
- 手动重试：`MusicViewModel.retryFailedStreaming()`（controller.play() 触发重新 resolve）。

## 5. 日志（AppLogFileManager）

| 项 | 值 |
| --- | --- |
| 目录 | `context.filesDir/logs/` |
| 主文件 | `playback.log` |
| 错误文件 | `streaming-errors.log` |
| 单文件轮转 | 5 MB，保留 `.1`…`.5`（共 6 件） |
| 总目录上限 | **30 MB**（`MAX_TOTAL_BYTES`），超限删除最旧文件（`trimBudget` 纯函数） |
| 脱敏 | `api_key=***`、`Token="***"`、`Authorization: ***`、`Bearer ***`、`p=***/t=***/s=***`（Subsonic）、JSON `Pw`/`password`/`token` → `***` |

所有 `AppLogFileManager` 写路径都经过 `redact()`；单测覆盖 `AppLogFileManagerRedactionTest` 与 `AppLogFileManagerTrimBudgetTest`。

## 6. 测试（JVM，GitHub Actions）

CI `test` job：`./gradlew testGithubDebugUnitTest`。套件：

| 文件 | 覆盖 |
| --- | --- |
| `StreamingItemTypeTest` | Book/AudioBook 判定 |
| `AudiobookChapterOrderTest` | 集数排序（ParentIndex/Index/标题回退） |
| `BookResumeSelectorTest` | 续播章节选择（95% 完成回 0、跨章、未播放回 0） |
| `StreamingRecoveryPolicyTest` | 重试次数/退避/seek 帧定位/流 ID 判定 |
| `AppLogFileManagerRedactionTest` + `AppLogFileManagerTrimBudgetTest` | 脱敏正则、30MB 预算 |

## 7. 分支与提交

见 `AGENTS.md`：功能分支 `feat/emby-book-recovery`，禁止本地编译，CI 全绿后合入；提交信息遵循 conventional commits。
