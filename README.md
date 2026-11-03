# Cloudstream TR (cs-mrk)

Cloudstream 3 için güncel Türkçe dizi, film, anime ve canlı yayın sağlayıcıları.

## Kurulum

1. Cloudstream uygulamasını açın.
2. **Ayarlar > Eklentiler > Depo Ekle** bölümüne gidin.
3. Kurulum bağlantısını ekleyin:
   ```text
   https://raw.githubusercontent.com/mrkstevn-ui/cs-mrk/main/repo.json
   ```
4. Eklentiyi tek tıkla yükleyin veya güncelleyin.

> 💡 **Önemli Not:** Daha önce depoyu eklediyseniz veya hata aldıysanız, **Ayarlar > Eklentiler** sayfasındaki **Yenile (Döngü) simgesine** basarak depo listesini tazeleyebilir veya depoyu silip yeniden ekleyebilirsiniz.

---

## Kazıyıcı çekirdeği (v25)

Sağlayıcılar ortak sonuç doğrulaması, metadata tamamlama ve sınırlı HTML/JSON-LD medya alternatifi kullanır. Siteye özel adaptörler korunur. [Uygulanan kapsam ve cihaz test listesi](docs/scraper-handoff.md).

İnatTV/Patronspor için `domains.json` içindeki `api_url`, ana site adresinden bağımsızdır. Güncelleyici bu alanı kaynak koddaki `apiUrl` ile eşitler; API şemasını canlı olarak doğrulamaz.

## Alan adı bakımı

`domains.json` sağlayıcıların alan adı ve onaylı ayna listesini tutar. Yeni alan adını sahipliğini kontrol ettikten sonra `current_url` veya `mirrors` listesine ekleyin.

```sh
python3 -m unittest discover -s tests
python3 scripts/update_domains.py          # Salt okunur kontrol
python3 scripts/update_domains.py --apply  # Doğrulanan adresleri Kotlin dosyalarına uygula
```

Kontrol HTTPS sertifikasını, yönlendirme hedefini ve sayfa başlığındaki sağlayıcı adını doğrular. Tanınmayan alan adına yönlendirme, bot doğrulaması veya bağlantı hatası güncelleme olarak kabul edilmez. Sayfa başlığı kontrolü tek başına alan adı sahipliğinin kanıtı değildir; ayna listesini yalnızca güvendiğiniz adreslerle güncelleyin. Ortak eklenti sürümü bir çalıştırmada yalnızca bir kez artırılır. Bazı sağlayıcılar doğrulanamazsa diğer doğrulanan güncellemeler uygulanır; komut başarısız çıkış koduyla doğrulanamayanları raporlar.

Otomatik görev 12 saatte bir çalışır. Alan adı kontrolü arama, detay ve video oynatma testinin yerine geçmez; sitenin HTML/API veya video sunucusu değişirse sağlayıcı kodunda da düzenleme gerekir. Aşağıdaki durumlar canlı test garantisi değildir.

## Sağlayıcılar

| Sağlayıcı | Tür | Durum |
| :--- | :--- | :--- |
| **HDFilmcehennemi** | Film, Dizi | ✅ Aktif |
| **FilmModu** | Film | ✅ Aktif |
| **Dizigom** | Dizi, Film, Anime | ✅ Aktif |
| **KültFilmler** | Film, Dizi | ✅ Aktif |
| **DiziBOX** | Dizi | ✅ Aktif |
| **AnimeciX** | Anime, Film | ✅ Aktif |
| **ÇizgiMax** | Çizgi Dizi | ✅ Aktif |
| **DiziFilmizle** | Film, Dizi | ✅ Aktif |
| **BelgeselX** | Belgesel | ✅ Aktif |
| **DiziGecesi** | Dizi, Film | ✅ Aktif |
| **DiziPal** | Dizi, Film | ✅ Aktif |
| **FullHDFilmizlesene** | Film | ✅ Aktif |
| **FilmKovası** | Film | ✅ Aktif |
| **HDFilmDelisi** | Film | ✅ Aktif |
| **RareFilmm** | Dünya Sineması | ✅ Aktif |
| **DiziYou** | Dizi, Film | ✅ Aktif |
| **İnatTV** | Canlı TV, Spor | ✅ Aktif |
| **Patronspor** | Canlı TV, Spor | ✅ Aktif |
| **SetFilmIzle** | Dizi, Film | ✅ Aktif |
| **SezonlukDizi** | Dizi | ✅ Aktif |
