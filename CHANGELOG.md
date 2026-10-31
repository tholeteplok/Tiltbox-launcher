# Changelog — Tiltbox Launcher

Semua perubahan penting dicatat di sini mengikuti format [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [v1.8.0] — 2026-10-31

### Ditambahkan
- **Local Theme Engine**: Sistem token CSS dinamis untuk lima tema built-in (Obsidian Dark, Malam Natal & Salju, Lentera Fitri, Pure OLED Black, Emerald Cinema).
- **Baris Tema & Suasana (`#laneThemes`)**: Baris ke-3 beranda berisi toggle musiman kalender, katalog tema, dan pintasan ke Toko Tema Cloud.
- **Auto-Seasonal Switcher**: Deteksi kalender otomatis untuk tema perayaan (Natal 15 Des–5 Jan, Lebaran 15 Mar–15 Apr).
- **Cloud Theme Store Modal (`#themeStoreModal`)**: Antarmuka toko tema online dengan koneksi ke repositori GitHub CDN, filter chip (Semua, Hari Raya, Gratis, Premium, Terpasang), unduhan satu klik, dan integrasi ke baris beranda.
- **Tema Cloud Komunitas (5 tema)**: Lampion Oriental Imlek, Nusantara Merdeka, Spooky Midnight Lantern (Gratis); Cyberpunk Neon Tokyo, Retro Synthwave 80s (Premium dengan QR Scan & Lisensi).
- **Aktivasi Premium via QR Smartphone**: Dialog scan QR Code dan kotak kode lisensi 6 digit tanpa perlu mengetik data kartu di remote TV.
- **Ukuran Kartu Mandiri S/M/L/XL**: Pengguna dapat mengatur ukuran kartu Sumber & Input dan Aplikasi secara independen termasuk mode Hero Portrait Poster (XL).
- **In-App Updater**: Sistem pembaruan otomatis via GitHub Releases API dengan indikator progres unduhan real-time dan installer native Android TV.

### Diperbaiki
- Kompatibilitas WebView Chromium <80 (Android TV lama): Menghapus optional chaining (`?.`), nullish coalescing (`??`), dan properti CSS `inset:0`.
- Dekupling jam digital dari input manual kota cuaca (jam tidak lagi bergantung pada data cuaca).

### Arsitektur Ekosistem
- Repositori `tholeteplok/Tiltbox-themes` diluncurkan sebagai CDN tema cloud komunitas.
- GitHub Actions CI/CD otomatis build & publish APK saat tag `v*` di-push.

---

## [v1.7.0] — 2026-10-30

### Ditambahkan
- Adaptive Brand Color Silhouettes untuk kartu aplikasi populer (YouTube, Netflix, Spotify, Vision+, dll.).
- Mode cuaca otomatis berbasis lokasi jaringan TV.
- Wallpaper lokal dari file gambar pengguna (downscale ke 1280×720 JPEG untuk keamanan RAM).

---

## [v1.6.2] — 2026-10-29

### Diperbaiki
- Header clearance dan spotlight fokus kartu.
- Penambahan padding branding Tiltbox yang konsisten.
