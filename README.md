# Ilha — Dynamic Island para o Galaxy A36 (Android 17)

Implementação do documento de arquitetura v1.0: app nativo em Kotlin + Compose, 100% local (sem permissão de INTERNET), sem root.

> **Estado honesto do código:** foi escrito sem acesso a um SDK Android, então **não foi compilado nem testado em aparelho**.
> Passou por revisão manual e por uma checagem estática (chaves balanceadas, imports internos resolvendo), mas espere
> corrigir alguns erros de compilação no primeiro build. Os pontos mais prováveis estão em “Onde olhar primeiro”.

## Como abrir e rodar
1. Android Studio (Narwhal ou mais novo), **Open** na pasta `ilha/`. O arquivo `gradle/wrapper/gradle-wrapper.properties` já aponta para o Gradle 8.13, que o Studio baixa sozinho. Faltam o `gradlew` e o `gradle-wrapper.jar` (são binários): para compilar pelo terminal, rode `gradle wrapper --gradle-version 8.13` uma vez.
   (Kotlin 2.3.21 e Compose BOM 2026.04.01 acompanham o que a biblioteca de vidro 2.0.0 pede. O AGP continua 8.13; se o Studio sugerir atualizar, pode aceitar.)
2. Rode o build `debug` no A36. O `applicationId` de debug é `app.ilha.debug`.
3. No app: ative **Acesso a notificações**, **janela acima da barra de status** (acessibilidade) e, se quiser, **Chamadas** e **Bluetooth**.
4. APK fora da Play: se o Android bloquear a ativação, vá em *Informações do app → ⋮ → Permitir configurações restritas*.
5. One UI: em Bateria, coloque a Ilha em “Apps que nunca dormem”.
6. Abra **Laboratório** e dispare cada tipo de atividade. Use **Calibrar** se a ilha não encaixar na câmera.

Atalho para desenvolvimento (ADB, build debug):
```
adb shell appops set app.ilha.debug ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd notification allow_listener app.ilha.debug/app.ilha.service.IslandNotificationListener
adb shell settings put secure enabled_accessibility_services app.ilha.debug/app.ilha.overlay.IslandAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell appops set app.ilha.debug SYSTEM_ALERT_WINDOW allow
adb shell pm grant app.ilha.debug android.permission.READ_PHONE_STATE
adb shell pm grant app.ilha.debug android.permission.ANSWER_PHONE_CALLS
adb shell pm grant app.ilha.debug android.permission.BLUETOOTH_CONNECT
```

## Compilar no GitHub (sem instalar nada)
O repositório já traz `.github/workflows/build.yml`: a cada push na `main` (ou em *Actions → Build APK → Run workflow*) o GitHub compila e guarda o APK em *Artifacts → ilha-debug-apk*. Cada build assina o APK de debug com uma chave nova: desinstale a versão antiga antes de instalar a nova.

## O que está implementado
Fontes → providers → motor → overlay, como no documento.
- **Providers:** mídia, chamadas, mensagens (com respostas rápidas por toque), timer/cronômetro/alarme, carregamento (watts e tempo até 100%), progresso/downloads, navegação, Live Updates (Android 16+), Bluetooth, silencioso/vibração e **Laboratório** (atividades falsas + teste de estresse).
- **Motor** (`core/engine`, Kotlin puro, com testes em `ArbiterTest`): Registry → Regras → Árbitro → `StateFlow<IslandUiState>`. Sem polling: reavalia só nos instantes de TTL/fim do alerta. Chamada ignora o debounce.
- **Dois hospedeiros** atrás de `OverlayHost`: modo A (`TYPE_ACCESSIBILITY_OVERLAY`, acima da barra) e modo B (`TYPE_APPLICATION_OVERLAY`). O `HostCoordinator` escolhe sozinho e cai para o B se a Proteção Avançada do Android 17 estiver ligada.
- **Gestos:** toque expande; deslizar para baixo expande; deslizar para cima recolhe (ou dispensa no compacto); esquerda/direita troca de atividade na fila; toque longo abre o app de origem; toque fora recolhe.
- **App:** permissões com divulgação proeminente, calibração, regras por app, funções liga/desliga, desempenho, diagnóstico.

## Visual do app: Liquid Glass
O app (não a ilha) usa [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop:2.0.0`). O outro repositório citado, QWEA0/Liquid-Glass-Android, é para o sistema de Views e o próprio README dele manda usar o do Kyant em apps Compose, então não entrou.
- **Fundo:** aurora estática gravada uma vez na camada do backdrop (`rememberLayerBackdrop` + `layerBackdrop`). Sem animação contínua, para não gastar bateria.
- **Vidro de verdade** (`drawBackdrop` com `vibrancy`, `blur`, `lens`): grupos de configurações, botões, seletores segmentados, chip de status e a barra inferior com indicador que escorrega em mola.
- **Interruptor** estilo iOS 26 (o miolo estica ao segurar), feito à mão.
- **Três níveis em *Aparência do app*:** Vidro (shader), Leve (translúcido com borda em degradê, sem shader) e Sólido.
- **O app cai para o vidro leve sozinho** no Laboratório e na economia de bateria: o vidro e a ilha dividem a mesma RenderThread, e o teste precisa medir a ilha, não o app. Listas longas (aba Apps) também usam o vidro leve.
- **A ilha continua preta, sem vidro e sem shader**, para fechar quadros a 120 Hz. O estilo está em `ui/Glass.kt`; o backdrop fica guardado numa lambda ali, então trocar de versão da biblioteca mexe em um lugar só.
- Se o build reclamar de algo na biblioteca (`drawBackdrop`, `onDrawSurface`, imports `com.kyant.backdrop.*`), a causa mais provável é diferença de API do 2.0.0 em relação à documentação que usei; o ajuste fica só em `Glass.kt`.

## Por que a animação é fluida (e o que é desligado sozinho)
- Largura, altura e raio da forma são `Animatable` lidos **só na fase de desenho**: sem recomposição e sem relayout por quadro. Largura e altura usam molas diferentes (é o que dá o ar líquido do iPhone).
- O conteúdo tem layout fixo no tamanho final e só anima alpha/escala (`graphicsLayer`, `ModulateAlpha`, sem buffer fora da tela).
- A janela do WindowManager **só muda de tamanho nas pontas**: cresce antes de animar, encolhe depois de assentar. Durante a animação pede 120 Hz (`preferredRefreshRate`); parado, solta.
- Sem blur, sem sombras, sem RenderEffect. Equalizador e spinner rodam a no máximo 30 fps, com `delay` (não acordam a cada vsync).
- **PerformanceGovernor:** mede quadros perdidos via Choreographer só durante as animações e desce FULL → BALANCED → LITE sozinho (rebote e escala somem primeiro, depois o equalizador, por fim tween curto). Também reduz com calor do aparelho e economia de bateria, e sobe de volta quando fica estável. Dá para travar o nível em *Desempenho*.

## Android 17 e política
- Com a **Proteção Avançada** ligada, o Android 17 bloqueia a API de acessibilidade para apps que não são ferramentas de acessibilidade. A Ilha detecta isso e usa o modo B (abaixo da barra de status, sem cobrir relógio e ícones).
- `targetSdk` 36 (exigido pela Play em 2026); o app roda normalmente no API 37. Confirme no Play Console o alvo exigido na data do envio.
- Para a Play: o serviço não marca `isAccessibilityTool` e não pede `canRetrieveWindowContent`; há divulgação proeminente antes de mandar o usuário às configurações. Falta só a declaração e o vídeo no Play Console.

## Diferenças em relação ao documento
- **Sem Hilt**: injeção manual em `AppGraph` (menos peças no build; a migração é direta).
- **Sem Room**: regras por app no DataStore (suficiente hoje; Room entra se virar histórico).
- **Módulo único** com pacotes espelhando os módulos do documento (o próprio documento permite dividir depois).
- `LiveActivity` ganhou `providerId`, `appLabel` e `alertMs`; `Slot.Timer` aceita contagem progressiva; `Slot.Glyph` foi adicionado.
- Resposta de mensagens por **chips de resposta rápida**: janela de overlay não abre teclado. Resposta livre abre o app.
- O estado compacto com `secondary` existe no motor, mas a “bolha satélite” não é desenhada: exigiria uma janela mais larga que bloquearia toques na barra de status.
- Não implementados (fases 2/3 do documento): gravador de voz/tela, API para terceiros, backup de ajustes, histórico de eventos, temas.

## Onde olhar primeiro se o build reclamar
1. `View`/`WindowManager` do `WindowOverlayHost` e do `CutoutProvider` (APIs 30+).
2. `NotifActions` (`ActivityOptions.setPendingIntentBackgroundActivityStartMode`) e `MessageProvider` (`getMessagesFromBundleArray`).
3. Versões em `libs.versions.toml`.

## Limites conhecidos
- Sem root não dá para desligar o heads-up nativo de outros apps (desative nos ajustes do sistema para não duplicar), esconder relógio/ícones, ou ler notificações marcadas como sensíveis (OTP).
- Vídeos em tela cheia: a ilha não detecta imersivo (isso exigiria ler a tela). Use as regras por app.
- Valores a calibrar no aparelho: largura do compacto, tempo dos alertas e o limiar de “jank” do governor (`PerformanceGovernor.report`).
