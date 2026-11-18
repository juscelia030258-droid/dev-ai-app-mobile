# LOCAL DEV AI — Documentação Técnica e Guia de Operação

Plataforma Android ARM64 de execução local de Inteligência Artificial com **três agentes especializados**, **orquestração multiagente**, **gerenciamento seguro de memória** e **servidor HTTP OpenAI-compatível permanente** na rede Wi-Fi local.

---

## 1. Arquitetura Modular

- `core/hardware`: `DeviceProfiler` — detecção real de ABI (`arm64-v8a`), núcleos de CPU, RAM total/disponível (`ActivityManager.MemoryInfo`), armazenamento livre (`StatFs`) e status térmico (`PowerManager`).
- `memory`: `MemoryBudgetManager` — reserva obrigatória de sistema operacional (nunca assume 100% dos 8 GB livres no Galaxy A56), estimativa de pegada GGUF/KV-cache e troca sequencial com descarregamento automático.
- `inference`:
  - `LlamaCppRuntime` (`org.nehuatl.llamacpp.LlamaHelper` / `llamacpp-kotlin:0.4.0`) — execução real local de modelos `.gguf` e visão multimodal com `.mmproj` em ARM64.
  - `LiteRtLmRuntime` (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`) — execução real oficial Google AI Edge `.litertlm` em CPU ou GPU OpenCL com fallback automático.
  - `ModelValidator` — verificação de assinatura binária `GGUF`, quantização, compatibilidade de RAM e presença de `mmproj` para visão.
- `model_manager`: `ModelRepository` — importação via Storage Access Framework (`OpenDocument`, sem permissões amplas de armazenamento), download HTTPS streaming com SHA-256 e catálogo recomendado para 8 GB RAM.
- `coding_agent` / `vision_agent` / `reasoning_agent` / `agent_engine`:
  - **Coding AI (`local-dev-coding`)**: geração, análise e revisão técnica de código.
  - **Vision AI (`local-dev-vision`)**: visão computacional multimodal real (rejeita modelos puramente textuais).
  - **Reasoning AI (`local-dev-reasoning`)**: planejamento, auditoria crítica (nunca aprova saídas vazias/incoerentes sem verificação) e consolidação.
  - **MultiAgentOrchestrator (`local-dev-multiagent`)**: comunicação por envelopes estruturados (`AgentStructuredEnvelope`), troca sequencial de modelos preservando contexto, loop limitado de revisões e timeout configurável.
- `security`: `ApiKeyManager` — geração automática única e persistente da Chave Principal Permanente protegida com **Android Keystore (`AES/GCM/NoPadding`)**, hash SHA-256, chaves adicionais por dispositivo com escopo granular e revogação instantânea.
- `api_server`: `OpenAiApiServer` (Ktor CIO) + `LocalNetworkDiscovery` (`ConnectivityManager.NetworkCallback`) + `LocalApiForegroundService` (`foregroundServiceType="specialUse"`).

---

## 2. Instalação e Build (macOS Monterey Intel x86_64 + Galaxy A56)

1. Exporte ou clone o projeto no MacBook Air (macOS Monterey 12.7.6, Android Studio / Gradle JDK 17+).
2. Gere o APK instalável:
   ```bash
   ./gradlew assembleDebug
   ```
   O APK gerado estará em `app/build/outputs/apk/debug/app-debug.apk`.
3. Instale no Samsung Galaxy A56 5G via USB/ADB ou transferência direta:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. No Galaxy A56, em **Configurações > Aplicativos > LOCAL DEV AI > Bateria**, selecione **Irrestrita** para evitar encerramento em segundo plano pela One UI.

---

## 3. Configuração do Qoder (MacBook Air -> Galaxy A56 via Wi-Fi)

1. Conecte o Galaxy A56 e o MacBook na mesma rede Wi-Fi.
2. Abra a aba **Modelos** no app e importe ou baixe os modelos locais (ex.: `Qwen2.5-Coder 1.5B Q4_K_M` para Coding AI, `SmolVLM 500M + mmproj` para Vision AI, `DeepSeek-R1 1.5B Q4_K_M` para Reasoning AI).
3. Abra a aba **API**, toque em **Iniciar Servidor** e copie:
   - **Base URL**: `http://<IP-DO-GALAXY-A56>:8080/v1`
   - **API Key**: chave permanente exibida na tela (`ldai_live_...`) ou chave dedicada criada para o MacBook (`ldai_dev_...`)
   - **Model IDs**:
     - `local-dev-coding` (programação)
     - `local-dev-reasoning` (raciocínio)
     - `local-dev-multiagent` (coordenação multiagente)
     - `local-dev-vision` (visão multimodal)
4. No Qoder, configure um provedor **OpenAI-Compatible** usando a **Base URL**, a **API Key** e o **Model ID** desejado.

---

## 4. Limitações Conhecidas e Escopo Real

- **Subset OpenAI suportado**: `GET /v1/models` e `POST /v1/chat/completions` (com streaming SSE `stream: true`, respostas completas `stream: false` e imagens base64 `image_url`). Endpoints como `/v1/embeddings`, `/v1/audio/*` e tool-calling nativo OpenAI não são suportados nesta versão.
- **Execução sequencial em 8 GB RAM**: apenas um modelo permanece carregado na RAM por vez no Galaxy A56; trocas entre Vision/Coding/Reasoning no modo multiagente descarregam o modelo anterior de forma segura.
- **Segundo plano Android**: apesar do Foreground Service `specialUse` e WakeLock parcial, políticas agressivas de economia de bateria do fabricante podem encerrar processos se a bateria não estiver configurada como Irrestrita.
