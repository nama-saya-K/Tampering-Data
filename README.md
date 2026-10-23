# TamperDroid

Proxy intercept lokal untuk Android (Kotlin) — mirip Tamper Data / Burp Proxy /
SandroProxy, tapi:
- Murni Kotlin + BouncyCastle, **tanpa root, tanpa VPN service** (pakai mode
  proxy manual klasik, sama seperti Burp/Tamper Data bekerja di browser).
- Belum pernah dicompile/di-test oleh pembuatnya (ditulis di lingkungan tanpa
  Android SDK). Anggap ini kerangka awal yang solid, bukan APK siap pakai.

## Fitur
- HTTP & HTTPS proxy lokal (port bisa diubah, default 8888).
- TLS MITM: generate root CA sekali di awal, lalu generate leaf cert per-host
  secara dinamis (persis pola mitmproxy/Burp).
- Log semua request/response (list di MainActivity).
- Mode **Intercept**: kalau diaktifkan, request ditahan sebelum dikirim —
  bisa diedit method/path/headers/body di DetailActivity, lalu Forward atau
  Drop (persis Burp Proxy "Intercept is on").
- Export CA cert dari dalam app buat diinstall ke device.

## Build
1. Push folder ini ke repo GitHub.
2. Build via Android Studio (Open project → Run), ATAU via GitHub Actions
   dengan workflow standar `gradle assembleDebug` (butuh Java 17 + Android
   SDK di runner — pakai action `android-actions/setup-android` atau image
   `reactivecircus/android-emulator-runner` punya SDK preinstalled; paling
   gampang pakai `./gradlew assembleDebug` di action `ubuntu-latest` dengan
   setup-java + cmdline-tools).
3. APK hasil build ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Pakai
1. Install APK, buka app, tekan **Start** (port default 8888).
2. Di device yang sama (atau device lain di jaringan sama, pakai IP app ini):
   Settings → Wi-Fi → edit network → Proxy manual → Host = IP device ini,
   Port = 8888.
3. Tekan **Export / install CA certificate** di app, lalu install cert itu
   lewat Settings → Security → "Install a certificate" (atau sejenisnya,
   beda-beda per vendor Android).
4. Buka browser / app target → traffic-nya akan muncul di list MainActivity.
5. Toggle **Intercept** kalau mau menahan & edit request sebelum
   diteruskan (seperti Burp Proxy).

## Keterbatasan yang perlu kamu perbaiki sendiri
- **Tidak ada bypass certificate pinning.** App yang pinning cert-nya sendiri
  (banyak app perbankan/non-browser) akan gagal handshake ke proxy ini.
  Solusi umum untuk itu pakai Frida + script unpinning — di luar scope kode
  ini.
- **Chunked transfer-encoding belum ditangani**, hanya `Content-Length`.
  Tambahkan parsing chunked kalau target situsmu memakainya.
- **HTTP/2 dimatikan paksa** (ALPN dipaksa http/1.1) supaya parsing
  teks sederhana di atas tetap jalan. Kalau target cuma bisa H2, perlu
  library HTTP/2 beneran (mis. OkHttp sebagai upstream client).
- Belum ada persistence (Room/SQLite) — history hilang kalau app di-kill.
  Gampang ditambah kalau perlu riwayat permanen.
- Belum ada fitur "Repeater" (resend manual, ubah-ubah, kirim ulang) —
  tinggal tambah tombol di DetailActivity yang buka exchange baru ke upstream
  tanpa menunggu request baru masuk, kalau kamu mau.

## Etika pemakaian
Pakai ini cuma untuk situs/app yang kamu sendiri punya, atau yang kamu punya
izin eksplisit untuk diuji (misal situs WordPress kamu di InfinityFree).
Mencegat traffic orang lain tanpa izin melanggar hukum di hampir semua
yurisdiksi.
