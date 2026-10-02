# Upstream senkronizasyon planı: srtla_send v3.0.0 → v4.1.0

Bu port `irlserver/srtla_send` commit `80cd0c4` (v3.0.0) üzerine kuruldu.
Upstream o tarihten bu yana 169 commit aldı ve v4.1.0'a (`5f2e081`, 2026-10-01) ulaştı.
Bu belge farkları özetler ve Kotlin tarafındaki uygulamayı fazlara böler.

## Upstream'de ne değişti

### Mimari
- Workspace üç crate'e bölündü:
  - `srtla-protocol`: saf wire format.
  - `srtla-core`: sans-IO çekirdek (connection, selection, registration, priority).
  - `srtla_send`: I/O kabuğu.
- Çekirdekte global saat okunmuyor. Her fonksiyon `now_ms` parametresi alıyor.
  `now_ms()` artık monotonic (`Instant` tabanlı). NTP adımında geri gitmiyor.
- Uplink soketleri artık **connect edilmiyor**. Her gönderim alıcı adresini açıkça veriyor. Alım her kaynaktan kabul ediliyor (NAT ve C referans uyumu için).
- Yerel SRT listener, IP dosyası okunmadan ve uplink'ler açılmadan **önce** bind ediliyor.

### Kaldırılanlar
- `rtt-threshold` ve `edpf` modları kaldırıldı. Yalnız `classic` ve `enhanced` kaldı.
- BLEST, IoDS, EDPF, exploration ve switch cooldown kaldırıldı.
- Metin tabanlı kontrol protokolü kaldırıldı. Yerine JSON-RPC 2.0 geldi.

### Protokol düzeltmeleri
- SRT NAK kayıp listesi offset 16'dan okunuyor (önceden 4, yanlıştı).
- NAK aralık sonu doğrulanıyor: MSB set ise ya da `end < start` ise aralık atlanıyor. 1000 ID tavanı korunuyor.
- SRT veri paketinde retransmit (R) biti okunuyor ve yazılabiliyor.
- SRT HSv5 conclusion handshake'inden TSBPD gecikmesi okunuyor (`negotiated_latency_ms`).
- ACK sıralaması 31-bit seri aritmetikle yapılıyor (`seq.rs`). Wrap sonrası ACK'ler artık yok sayılmıyor.

### Çekirdek davranış
- **RTT:** Üç kaynak tek `record_round_trip` üzerinden geçiyor: SRT ACK (yalnız seq sahibi link), SRTLA ACK ve keepalive. 0 ms ve 10 s üstü örnekler reddediliyor. MASD ve queue-building dedektörü eklendi.
- **Reconnect:** İlk 4 deneme 1 s arayla, sonra 5 s arayla. Yarım tick tolerans var. Sayacı yalnız REG3 sıfırlıyor.
- **LinkPhase:** Registering, Warming, Live, Degraded. Faz artık skor ağırlığı, eleme kapısı değil (Warming = 0.8).
- **Stall gate:** RTT'ye uyarlanan staleness penceresi, asimetrik latch ve rejoin dwell. Backoff, rejoin ramp, hızlı silence pull ve gated link'e 1/100 duplicate probe eklendi. Probe'lar ayrı `probe_log`'da tutuluyor.
- **Weak-link classifier:** Üç gecikme katmanı, throughput payı, hysteresis, probation re-test ve backoff.
- **Link CC:** Climbing, Holding, BackingOff, Drain durumları. HAI ve FastRecovery tırmanma modları. Kayıp EWMA'sı `loss_degraded` latch'ini besliyor.
- **Enhanced selection:** Late (gecikmeli) link payload'dan dışlanıyor. Share-weak link 0.02 cezayla trickle trafik alıyor. Ayrıca BDP in-flight cap, CC soft cap, rejoin ramp ve sticky sole-carrier seçimi var.
- **Classic selection:** IP dosyasından gelen link ağırlıkları uygulanıyor (Moblin bağlantı öncelikleri).
- **Registration güvenliği:** REG3 ve REG_ERR yalnız beklenen fazda kabul ediliyor. REG3 izni tek kullanımlık. `reset_for_rehome` eklendi.
- **Priority sidecar:** UDP üzerinden 5 baytlık critical window. Pencere içindeki ve retransmit paketleri en kaliteli linke gidiyor.
- **İstemciye iletim:** Her uplink'ten gelen aynı ACK ve NAK kopyaları istemciye yalnız bir kez iletiliyor (ACK 1000 ms, NAK 50 ms pencere).
- **Recovery limbo:** Recovery'deki bir link alıcı trafiğiyle canlı sayılmıyor.
- **Flush hatası:** Gönderim hatası her flush yolunda linki recovery'ye alıyor.
- **Bitrate:** Bitrate'e göre batch regime seçiliyor (4/16/32).
- **Re-home:** Bond tamamen ölü kaldığında ve DNS adresi değiştiğinde tüm uplink'ler birlikte yeni adrese taşınıyor (`--no-rehome` ile kapatılır).
- **Reload:** Sıfır geçerli IP içeren bir reload reddediliyor. IP dosyası `<ip> [weight]` biçimini kabul ediyor.

### Kabuk ve operasyon
- JSON-RPC 2.0 kontrol protokolü stdin ve Unix soket üzerinden çalışıyor. Abonelikler (`stats`, `priority.window`) eklendi.
- Prometheus `/metrics` uç noktası eklendi.
- `--config` ile TOML dosyası okunuyor. Bilinmeyen anahtar başlatmayı durduruyor.
- Yeni CLI bayrakları: `--no-stall-deselect`, `--stall-min-in-flight`, `--stall-ack-stale-ms`, `--conn-timeout-ms`, `--no-rehome`, `--priority-bind`, `--metrics-bind`.
- SIGTERM ve SIGINT ile temiz çıkış yapılıyor.
- Sürüm satırında "unknown" yazmıyor.

## Kotlin uygulama fazları

Her faz derlenir ve testkit suite'i yeşil kalır.

| Faz | Kapsam | Upstream kaynak |
|---|---|---|
| 0 | Altyapı: monotonic `Clock`, `Seq` seri aritmetik, `SchedulingMode` (classic ve enhanced), `ConfigSnapshot` sabitleri | `utils.rs`, `seq.rs`, `mode.rs`, `config_snapshot.rs` |
| 1 | Protocol: NAK offset 16 ve doğrulama, R biti, handshake latency parser, saat enjekte edilen keepalive | `srtla-protocol/*` |
| 2 | Connection çekirdeği: Bitrate, RTT, Reconnection, Congestion, BatchSender, SrtlaConnection (fazlar, stall latch, probe, ramp, ağırlık), seri ACK/NAK | `srtla-core/src/connection/*` |
| 3 | Registration: faz kapısı, saf builder'lar, probing, `resetForRehome` | `srtla-core/src/registration/*` |
| 4 | Selection: classic ağırlıklar, enhanced, quality, classifier, link_cc, priority. Eski modlar silinir. | `srtla-core/src/selection/*`, `priority.rs` |
| 5 | Sender kabuğu: NIO Selector event loop, connect edilmeyen soketler, `UplinkBinder`, packet handler, uplink recv, housekeeping, connections, rehome, reload, client dedup | `src/sender/*`, `src/net/*` |
| 6 | Config, kontrol ve telemetri: DynamicConfig, mini JSON, JSON-RPC (stdin, TCP, Unix soket), abonelikler, priority sidecar, Prometheus, SharedStats, TOML, CLI, sürüm, sinyaller | `config.rs`, `control*.rs`, `subscriptions.rs`, `metrics.rs`, `stats.rs`, `toml_config.rs`, `main.rs`, `version.rs` |
| 7 | Testler (upstream testlerinin portu ve E2E), dokümanlar (README, NOTICE, CONTROL_PROTOCOL, KEYFRAME_PRIORITY), CI | `src/tests/*`, `docs/*` |

## JVM sapmaları

| Upstream | Kotlin | Neden |
|---|---|---|
| `sendmmsg` / `recvmmsg` | Paket başına `DatagramChannel.send`/`receive`, tek NIO Selector | JVM'de batch syscall yok. BatchSender kuyruğu ve skor etkisi korunur. |
| tokio `select!` event loop | Tek thread, `Selector.select(timeout)`, housekeeping (1 s) ve flush (15 ms) zamanlayıcıları | Upstream'deki tek görevli, kilitsiz modelin karşılığı |
| Unix domain kontrol soketi | `--control-socket` JDK 16+ üzerinde reflection ile. Ek olarak `--control-port` (loopback TCP). | Hedef JDK 11 ve Android |
| SIGHUP | Mümkünse `sun.misc.Signal` (reflection), yedek olarak WatchService | Taşınabilir sinyal API'si yok |
| serde / toml / clap | Elle yazılmış mini JSON, mini TOML ve argüman ayrıştırıcı | Sıfır bağımlılık ilkesi |
| Apple `IP_BOUND_IF` binder | Yok. `UplinkBinder` arayüzüyle host tarafında çözülür. | JVM erişemez |
| network-sim / netns testleri | Port edilmedi | Linux netns gerektirir |
| proptest | Sabit seed'li rastgele parser fuzz testi | Sıfır bağımlılık |
