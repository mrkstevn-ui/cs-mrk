# Cloudstream TR (cs-mrk)

Bu depo, Cloudstream 3 için güncel ve reklamsız çalışan Türkçe dizi, film, anime ve belgesel sağlayıcılarını barındırır.

## Cloudstream'e Ekleme

1. Cloudstream uygulamasını açın.
2. **Ayarlar (Settings) > Eklentiler (Extensions) > Depo Ekle (Add Repository)** bölümüne gidin.
3. Şu bağlantıyı ekleyin:
   ```text
   https://raw.githubusercontent.com/mrkstevn-ui/cs-mrk/builds/plugins.json
   ```
4. Eklenti listesinden dilediğiniz sağlayıcıyı tek tıkla yükleyin.

---

## Aktif Sağlayıcılar

| Sağlayıcı | Tür | Durum | Özellikler |
| :--- | :--- | :--- | :--- |
| **HDFilmcehennemi** | Film, Dizi | ✅ Aktif | Full HD Posterler, Özel Rhino Deobfuscation, ExoPlayer 3003 Korumalı |
| **FilmModu** | Film | ✅ Aktif | Vidmixi AES-256 Deobfuscation, Türkçe/İngilizce Altyazı, Çift Ses |
| **Dizigom** | Dizi, Film, Anime | ✅ Aktif | Pilavyer Player, Tüm Sezon ve Bölümler, Çift Kanal Ses |
| **KültFilmler** | Film, Dizi | ✅ Aktif | Vidpapi FirePlayer HLS / Vidmoly Entegrasyonu |
| **DiziBOX** | Dizi | ✅ Aktif | MolyStream Sheila 1080p M3U8, Hızlı Arama, Tüm Sezonlar |
| **AnimeciX** | Anime, Film | ✅ Aktif | REST API, Doğrudan 1080p/720p/480p MP4 Akışı |
| **ÇizgiMax** | Çizgi Film, Anime, Dizi | ✅ Aktif | NP-Resolve & Tau-Video 1080p Doğrudan MP4 / Vidmoly |
| **DiziFilmizle** | Film, Dizi | ✅ Aktif | Next.js SSR Ayrıştırma, Vidmixi 1080p HLS ve Altyazı |
| **BelgeselX** | Belgesel | ✅ Aktif | Zengin Belgesel Arşivi, Google Drive / MP4 Oynatıcı |
| **DiziGecesi** | Dizi, Film | ✅ Aktif | AJAX Embed Ayrıştırıcı, Vidmoly & Filemoon Çoklu Alternatifler |
| **DiziPal** | Dizi, Film | ✅ Aktif | PBKDF2 HMAC-SHA512 & AES-256 Şifre Çözücü, DPlayer 1080p Master HLS |
| **FullHDFilmizlesene** | Film | ✅ Aktif | ROT13 & RapidVid Deobfuscator, 1080p Çift Ses Master HLS |
| **FilmKovası** | Film | ✅ Aktif | Base64 Player Ayrıştırıcı, Odnoklassniki (OK.ru) Çoklu Kalite MP4/HLS |
| **HDFilmDelisi** | Film | ✅ Aktif | Doğrudan 1080p Full HD MP4 Akışı, Hızlı Arama, Zengin Arşiv |
| **RareFilmm** | Film | ✅ Aktif | Nadir & Klasik Dünya Sineması Arşivi, OK.ru 1080p/720p Oynatıcı |

---

## Otomatik Domain Güncelleyici (Bot)

Sitelerin alan adı (domain) engellemelerine ve adres değişikliklerine karşı otomatik bot devrededir:
- **`scripts/update_domains.py`**: Tüm sağlayıcıların mevcut adreslerini HTTP yönlendirmeleri, ayna (mirror) adresler ve sayısal artış örüntüleriyle test eder.
- **GitHub Actions (`.github/workflows/update_domains.yml`)**: Günde 2 kez otomatik veya istendiğinde manuel tetiklenerek değişen alan adlarını tespit eder, Kotlin kaynak kodunu günceller, eklenti sürümünü artırır ve otomatik olarak derleyip yayına alır.
