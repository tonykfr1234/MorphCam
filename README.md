# MorphCam

安卓 App 加上一台有顯卡的電腦，可以做四件事：

| 功能 | 在哪裡運算 | 技術 |
|---|---|---|
| 👕 即時換衣服，衣服跟著骨架動 | 手機 | MediaPipe Pose（33 個關節點），身體、袖子、褲管各自綁在對應的關節上 |
| 🎭 即時換臉（給一張照片） | 電腦 GPU | InsightFace buffalo_l 加上 inswapper_128 |
| 🧍 即時全身替換（給一張全身照） | 電腦 GPU | StreamDiffusion（SD1.5 + LCM，TensorRT）：OpenPose ControlNet 跟你的動作，IP-Adapter 學照片的外觀，最後用 inswapper 貼上照片的臉 |
| 🎙 聲音複製加即時變聲 | 電腦 GPU | Seed-VC（零樣本，不用訓練） |

```
手機 (CameraX / AudioRecord)  ── Wi-Fi WebSocket ──>  電腦 server (FastAPI + CUDA)
            <── 換好臉的 JPEG / 變好聲的 PCM ──
```

## 一鍵使用（Windows）

先裝好 [Git](https://git-scm.com/download/win)、[Python 3](https://www.python.org/downloads/)、[VS 2022 Build Tools（C++）](https://visualstudio.microsoft.com/visual-cpp-build-tools/)，然後在 PowerShell 貼上這一行：

```powershell
cd C:\; git clone https://github.com/tonykfr1234/MorphCam.git; C:\MorphCam\install.bat
```

之後在 `C:\MorphCam` 雙擊就能用：

| 檔案 | 用途 |
|---|---|
| `install.bat` | 安裝或重新安裝（主伺服器加全身替換，約 30 分鐘） |
| `start.bat` | 啟動：換臉、變聲（換衣服在手機上算，不需要電腦） |
| `start-fullbody.bat` | 啟動全身替換模式（獨佔 12 GB 顯卡） |
| `update.bat` | 從 GitHub 更新到最新版 |

## 硬體需求

| | 最低 | 建議 | 你的 RTX 3060 12GB |
|---|---|---|---|
| 顯卡 | NVIDIA GTX 1660 6GB（換臉約 8–12 fps，變聲延遲約 2 秒） | RTX 3060 12GB 以上 | ✅ 換臉約 15–25 fps，變聲延遲約 1–1.5 秒 |
| 顯示記憶體 | 6 GB（換臉和變聲不要同時用） | 8 GB 以上（三個模型可以同時載入） | ✅ 12 GB |
| 全身替換 | **12 GB 顯示記憶體**（RTX 3060 12GB / 4070 / 3080 12GB 以上），8 GB 的卡跑不動 | RTX 4070 Ti 以上會更快 | ✅ 實測 5.0 fps（每張約 200 ms），開這個模式時要獨佔顯卡 |
| 系統記憶體 | 16 GB | 32 GB | |
| 硬碟 | 15 GB 可用空間（Python 套件加模型） | SSD | |
| 系統 | Windows 10/11 64 位元，NVIDIA 驅動 ≥ 531 | | |
| 手機 | Android 8.0 以上，2019 年以後的中階機 | 有 GPU 加速的中高階機 | |
| 網路 | 手機和電腦在同一個 Wi-Fi，5 GHz 比較好 | | |

沒有 NVIDIA 顯卡（只有 AMD、Intel 內顯或 CPU）也跑得起來，但換臉每秒不到 1 張，即時變聲完全做不到。

## 一、電腦端（伺服器）

需要先安裝：
1. 任何版本的 [Python 3](https://www.python.org/downloads/)（需要 `py` 啟動器）。安裝腳本會用 uv 自動下載專用的 Python 3.10
2. [Git](https://git-scm.com/download/win)
3. [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)，勾選「使用 C++ 的桌面開發」（編譯 insightface 需要）
4. [ffmpeg](https://www.gyan.dev/ffmpeg/builds/)，加到 PATH（讀取手機的 m4a/mp3 錄音需要）

```powershell
cd C:\
git clone https://github.com/tonykfr1234/MorphCam.git     # 一定要放在短路徑，例如 C:\MorphCam
cd C:\MorphCam\server
powershell -ExecutionPolicy Bypass -File setup.ps1   # 只要做一次，約 10–20 分鐘
powershell -ExecutionPolicy Bypass -File run.ps1     # 啟動伺服器
```

> ⚠️ **路徑要短**：放在很深的資料夾（例如 Downloads 底下好幾層）會超過 Windows 260 字元的路徑限制，PyTorch 會載入失敗（WinError 206）。

### 全身替換（選用）

```powershell
cd C:\MorphCam\server\fullbody
powershell -ExecutionPolicy Bypass -File setup.ps1   # 只要做一次，約 15 分鐘
cd ..
powershell -ExecutionPolicy Bypass -File run.ps1 -FullBody
```

- 第一次啟動會建 TensorRT 引擎，**RTX 3060 約需 30–40 分鐘**，之後啟動只要 1–2 分鐘。
- 全身模式約占 11 GB 顯示記憶體，12 GB 的卡開全身模式時，不要同時用換臉或變聲。

啟動後畫面會顯示 `在手機 App 輸入: http://192.168.x.x:18765`，把這個位址填進 App。第一次換臉或變聲時會自動下載和載入模型，需要等 30–60 秒。

可以調整的環境變數：

| 變數 | 預設值 | 說明 |
|---|---|---|
| `MORPHCAM_PORT` | 18765 | 伺服器埠號 |
| `MORPHCAM_VOICE_BLOCK` | 1.0 | 變聲一次處理幾秒。調小延遲會降低，但顯卡負擔變重、音質變差 |
| `SEEDVC_DIR` | `server/third_party/seed-vc` | Seed-VC 的位置 |
| `MORPHCAM_BODY_PORT` | 18766 | 全身替換服務的埠號（只在本機，不對外） |
| `MORPHCAM_ENGINE_DIR` | `server/fullbody/engines/sd15` | TensorRT 引擎存放位置 |

## 二、手機端（APK）

- 每次推到 `main`，GitHub Actions 會自動編譯 APK，到 **Actions → 最新一次執行 → Artifacts → MorphCam-apk** 下載。
- 推一個 `v*` 標籤（例如 `git tag v0.1.0 && git push --tags`），APK 會放到 **Releases** 頁面。
- 想自己編譯的話，用 Android Studio 打開 `android/` 資料夾，按 Run。

## 使用說明

- **換衣服**：站遠一點，讓肩膀和臀部都在畫面裡。內建 6 套衣服，也可以選一張去背的 PNG 上衣（正面照，肩膀大約在圖片寬度 25%/75%、高度 15% 的位置）。
- **換臉**：按「選擇要變成的臉」，選一張正面清楚的照片。
- **全身替換**：按「選擇要變成的人」，選一張**從頭到腳的全身照**，背景越乾淨越好。站離鏡頭遠一點，讓全身都在畫面裡。畫面是 512×512，延遲約 0.2–0.4 秒。
- **變聲**：
  1. 輸入聲音主人的名字，取得同意聲明
  2. 由**本人**錄下同意聲明
  3. 選一段本人說話 10–25 秒的錄音
  4. 上傳。伺服器核對聲明內容，並確認是同一個人之後，才會建立聲音
  5. 戴上耳機，選擇聲音，開始即時變聲

## 目前做不到的部分

- **全身替換的品質**：畫面是 AI 重新畫出來的，所以：
  - 衣服顏色和款式、髮型、臉會像照片，但細節（衣服上的字、花紋）不會完全一樣。
  - 背景也是重新生成的，不是你房間原本的背景。
  - 解析度 512×512，大約 5 fps，看起來會有點跳格，還不到電影那麼順。
  - 動作太快或身體被擋住時，偶爾會畫錯手腳。
- 換衣服是 2D 貼圖，人轉身或手擋在身體前面時，衣服會蓋住手。
- 變聲是一段一段處理的，大約有 1–1.5 秒延遲，不適合快速對話。

## 使用規範（程式內建，不能關掉）

- 換臉和全身替換的畫面一定會燒進「AI-GENERATED」浮水印，App 畫面上也有「AI 生成」標籤。
- 複製聲音必須由聲音本人親自念同意聲明，並通過語音辨識和聲紋比對。
- 禁止用來冒充他人、詐騙、騷擾或製作未經同意的內容。使用者要自己負擔法律責任。
- `inswapper_128` 模型只能用於非商業用途（InsightFace 授權）。

## 專案結構

```
android/                Kotlin App（CameraX、MediaPipe、OkHttp）
  app/src/main/java/com/morphcam/app/
    ClothesActivity.kt  換衣服（姿勢追蹤和平滑）
    Garments.kt         衣服的繪製方式，綁在關節上
    FaceSwapActivity.kt 傳送畫面、顯示換臉結果
    VoiceActivity.kt    同意錄音、建立聲音、即時變聲
server/
  main.py               FastAPI，提供 REST 和 WebSocket
  face_swap.py          InsightFace 換臉
  voice.py              Seed-VC 分段串流轉換
  consent.py            同意聲明核對（Whisper 加 ECAPA 聲紋）
  watermark.py          浮水印
  fullbody/             全身替換（獨立虛擬環境，main.py 用 /ws/body 轉發過去）
    body_swap.py        姿勢偵測（CPU 執行緒）與 GPU 生成重疊執行的流水線
    worker.py           只聽 127.0.0.1 的 WebSocket 服務
    config.yaml         StreamDiffusion 設定（已在 RTX 3060 上調好）
    bench.py            用影片離線測速度和效果
```
