# Tiltbox Launcher v1.7.0 — Full Customizer & RT41 Optimized

Versi lengkap yang mencakup seluruh fitur kustomisasi dan perbaikan audit untuk **TCL Chassis RT41** (`Realtek RTD2841 / RTD2851`, Android TV 9 Pie `V8-R41KT01-LF1V343`):

1. **Bottom Bar Transparan & Dinamis**:
   - Menggunakan *Frosted Glass* semi-transparan (`rgba(28, 40, 44, 0.62)` + *specular border* `1px`).
   - **Auto-Hide saat Scroll Down** (baik lewat navigasi D-Pad turun ke baris bawah maupun *scroll* vertikal/wheel) dan **Auto-Show saat Scroll Up**.
2. **Kustomisasi Urutan & Grid**:
   - **Susunan Baris**: Tukar posisi `Sumber & Input` vs `Aplikasi Favorit`, serta sembunyikan/tampilkan baris `Sumber & Input` atau `Pengaturan Cepat`.
   - **Susunan Aplikasi Favorit (Maks 9) + Overlay Modal Grid Semua Aplikasi**: Batas tegas maksimal 9 aplikasi favorit di beranda dengan fitur Pin/Unpin (`★ Favorit`) dan geser urutan (`◀ ▶`) langsung dari modal Grid Semua Aplikasi.
3. **Kustomisasi Wallpaper**:
   - **5 Preset Sinematik** (`Obsidian Dark`, `Pure OLED Black`, `Emerald Cinema`, `Royal Midnight`, `Sunset Amber`).
   - **Wallpaper Gambar Lokal**: Mendukung pemilih gambar lokal (`<input type="file" accept="image/*">` yang terhubung ke `WebChromeClient.onShowFileChooser` di `MainActivity.kt`) dengan kompresi kanvas otomatis (`1280×720` JPEG) agar hemat RAM dan tersimpan di `localStorage`.

## Perubahan v1.7.0 — Cuaca nyata & Offline Font

- **Offline Font Mandiri**: Font Manrope WOFF2 (~24 KB) dibundel langsung di dalam aset APK (`assets/fonts/manrope.woff2`). Bebas dependensi CDN Google Fonts, mencegah FOUT/FOIT saat TV baru menyala offline, dan CSP terkunci penuh ke `font-src 'self' file:`.
- **Widget cuaca** di pojok kanan bawah menampilkan ikon dan suhu saat ini, dan nama kota muncul di sebelahnya. Widget bisa difokus dengan remote (footer: kanan dari tombol RAM) dan ditekan untuk membuka **panel detail**: kondisi, terasa seperti, kelembapan, angin + arah, curah hujan, indeks UV, matahari terbit/terbenam, prakiraan 8 jam ke depan, dan prakiraan 5 hari.
- **Lokasi**: otomatis lewat lokasi perangkat (izin *lokasi perkiraan*/COARSE, tidak butuh GPS), cadangan perkiraan dari alamat IP bila izin ditolak atau TV tidak punya penyedia lokasi, atau **pilih kota manual** (16 kota Indonesia) lewat tombol *Ganti lokasi*. Izin lokasi diminta sekali saat pertama kali; bila ditolak, tombol *Izinkan lokasi* di panel membuka pengaturan aplikasi.
- **Data**: [Open-Meteo](https://open-meteo.com/) (tanpa API key). Nama kota otomatis dari BigDataCloud (reverse geocoding). Cuaca diperbarui tiap 30 menit saat layar aktif dan saat kembali ke launcher; data terakhir disimpan sehingga langsung tampil saat dibuka.
- **Izin baru**: `INTERNET` dan `ACCESS_COARSE_LOCATION`. Semua koneksi dilakukan oleh kode native (bukan WebView); halaman web dibatasi Content-Security-Policy tanpa akses jaringan (`connect-src 'none'`).
- **Privasi**: koordinat (dibulatkan 4 desimal) dikirim ke Open-Meteo dan BigDataCloud hanya untuk mengambil cuaca dan nama kota; mode manual hanya mengirim koordinat kota pilihan. Tidak ada data lain yang dikirim.

## Perubahan v1.6.2

- **Input HDMI/AV nyata**: daftar port dan status (terhubung / standby / tidak ada sinyal / tidak terdeteksi) dibaca dari `TvInputManager`, termasuk nama kustom port dari pengguna. Pindah input memakai `TvContract` passthrough URI; cara lama (paket sumber TCL) tetap jadi cadangan.
- **RAM Booster jujur**: hanya mematikan proses latar aplikasi yang bisa dibuka pengguna (paket sistem/TCL dilindungi), mengukur RAM ±1 detik sesudahnya, dan menampilkan angka sebenarnya. Tidak ada lagi angka karangan.
- **Data palsu dihapus**: status statis di kartu sumber (+5000 Channels, USB-1, 1080p, dll.), widget cuaca 28°, titik "aktif" di semua aplikasi, dan aplikasi contoh saat bridge mengembalikan daftar kosong.
- Kategori aplikasi dibaca dari sistem Android; daftar aplikasi diurutkan alfabet; label zona waktu (WIB/WITA/WIT) dinamis.
- Wallpaper otomatis: opsi "15 detik (Demo)" dihapus dan default kini **Nonaktif** (pengaturan lama dimigrasikan otomatis).

## Perubahan v1.6.1 (hasil audit)

- **Build**: perbaikan `Build.VERSION.SDK_INT`, ikon launcher (`@mipmap/ic_launcher`) dan banner TV 320×180 ditambahkan, Gradle wrapper (8.9) disertakan.
- **Remote/D-Pad**: modal Kustomisasi & Semua Aplikasi kini bisa dioperasikan penuh dengan D-Pad (termasuk Pin/geser favorit); tombol header (mic, gear, avatar) dan tombol RAM di footer ikut terjangkau; fokus tidak lagi melompat saat menekan Atas/Bawah di baris paling ujung.
- **Wallpaper lokal**: pemilih gambar kini native (tanpa `<input type="file">`), gambar di-downscale hemat memori lalu disimpan sebagai file di penyimpanan internal app, bukan di `localStorage`. *Wallpaper lokal dari versi lama perlu dipilih ulang.*
- **Daftar aplikasi** otomatis diperbarui saat ada instal/hapus/update; favorit tidak lagi terhapus permanen ketika app sementara hilang saat update.
- **Keamanan**: nama/ikon aplikasi di-escape (mencegah injeksi HTML/JS ke halaman yang punya akses bridge), navigasi WebView ke luar `file:///android_asset/` diblokir, akses file/content WebView dimatikan, `allowBackup` dimatikan.
- **Stabilitas**: `launchMedia`/`launchVoiceSearch` tidak lagi bisa crash, renderer WebView yang mati dipulihkan otomatis, Enter/Back yang ditahan tidak memicu aksi berulang, tombol HOME saat sudah di launcher kembali ke posisi awal.
- Toast kini memberi tahu bila aplikasi/fitur gagal dibuka.

## Memasang di TV

```bash
adb connect <IP-TV>:5555
adb install -r TiltboxLauncher_v1.7.0-debug.apk
```

Lalu pilih Tiltbox sebagai launcher saat menekan tombol HOME (atau lewat Setelan → Aplikasi → Aplikasi default, tergantung firmware).
