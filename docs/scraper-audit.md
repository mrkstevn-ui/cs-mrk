# Sağlayıcı ve çözücü incelemesi — 2026-11-03

## Sonuç ve doğrulama sınırı

20 sağlayıcının Kotlin uygulaması kod üzerinden incelendi. Mevcut durum için “hepsi doğru veri çekiyor” sonucu çıkarılamaz. Önceki 7 Python testi yalnızca alan adı güncelleyicisini test eder; sağlayıcıları çalıştırmaz. DNS erişimi önceki canlı kontrolde başarısız oldu. Kotlin derleme denemesi de Gradle'ın kullanıcı önbelleğindeki kilit dosyasına yazma izni olmadığı için başlamadı. Aşağıdaki bulgular statik kod incelemesidir; canlı sitenin bugün aynı şemayı kullandığının kanıtı değildir.

Tek bir genel HTML kazıyıcısı bütün siteler, API değişiklikleri ve oynatıcı protokolleri için doğru sonuç garantisi vermez. Uygun hedef: ortak bir çalışma motoru, site ailesine özel adaptörler ve video sunucusuna özel çözücüler. Model değiştiğinde sistem yanlış sonuç üretmek yerine açık hata vermeli; bilinen alternatifleri kontrollü denemeli.

## Somut bulgular

1. **Video üretmeden başarı raporlanabiliyor.** `BelgeselX.kt:152`, `DiziBox.kt:189`, `CizgiMax.kt`, `KultFilmler.kt`, `HDFilmCehennemi.kt` ve `FilmModu.kt` içinde bazı `loadExtractor` çağrılarından sonra bağlantı callback'i çalıştı mı kontrol edilmeden başarı bayrağı atanıyor. `DiziFilmizle.kt` içindeki `resolveVidmixi` sonuç üretmeden dönebilir; çağıran yine başarı bayrağı atıyor. `Dizigom.kt:254` stream alanı boş olsa da normal akışta true döndürüyor. Çözüm: her sağlayıcının dış sınırında gerçekten üretilen geçerli video bağlantılarını saymak; sadece altyazı veya iframe bulunması video başarısı sayılmamalı.
2. **Site, API ve CDN adresleri farklı bağımlılıklar.** `InatTV.kt` ve `Patronspor.kt` içinde `patronsports2.cfd` API'leri ve varsayılan yayın CDN'i sabit. Ana adresi değiştirmek bu bağımlılıkları yenilemez. `FilmModu.kt:23` ve `Dizigom.kt:16` başlıklarında sabit Referer var. AnimeciX bootstrap `original_url` alanı `AnimeciX.kt:215` içinde sabit. Adres kataloğu site origin, API origin ve oynatıcı origin rollerini ayrı tutmalı; hepsini ana alan adıyla körlemesine değiştirmemeli.
3. **Sorgu kodlama eksik.** Örneğin `DiziBox.kt` ve `KultFilmler.kt` sorgu parametrelerinde, `DiziGecesi.kt:60` yol segmentinde boşluk değiştirme kullanıyor. `&`, `#`, `?`, `/`, `+` ve Türkçe karakterler sorgunun anlamını bozabilir. Query parametresi ve path segmenti ayrı URL oluşturucularıyla kodlanmalı.
4. **İçerik tipinin tespiti kırılgan.** Bazı `load` uygulamaları bölüm seçicisi boş gelince film döndürüyor. Site dizi listesini AJAX'a taşırsa dizi yanlışlıkla film olabilir. Tür URL, API/structured data ve adaptör bilgisiyle doğrulanmalı; eksik bölüm listesi tek başına film kanıtı değildir.
5. **Bölüm URL'si tahmin ediliyor.** `DiziYou.kt:147-162` sezon butonundaki sayıdan 1..N döngüsüyle bölüm adresi kuruyor. Atlanan bölüm, özel bölüm veya farklı slug için yanlış bağlantı üretilebilir. Gerçek bölüm kayıtları/API yanıtı tercih edilmeli; tahmin edilmiş kayıtlar doğrulanmadan gerçek bölüm gibi sunulmamalı.
6. **Göreli URL'ler sayfa bağlamında çözülmeli.** İç iframe veya altyazının göreli adresi ana siteye değil bulunduğu iframe'in son yanıt URL'sine göre çözülmeli. `RareFilmm.kt` yalnızca tam ve protokol göreli iframe adreslerini kabul ediyor; kök/yol göreli iframe'ler atlanıyor. Ortak çözümleyici, HTTP(S) şemasını doğrulayarak sayfa tabanına göre adres çözmeli.
7. **Sessiz hata ve yapay kalite bilgisi var.** Bazı catch blokları boş sonuç döndürüyor; birçok çözücü sabit 1080p yazıyor. Bağlantı varsa bile gerçek kalite doğrulanmış olmuyor. Hata aşamaları ayrılmalı; kalite kaynaktan veya manifestten gelmiyorsa bilinmiyor olmalı.
8. **Aşırı geniş seçiciler yanlış veri üretebilir.** `article`, ilk `img`, tüm sayfadaki yıl regex'i veya tüm sayfadaki bölüm linkleri reklam, öneri ve başka dizi kayıtlarını yakalayabilir. Örneğin `SetFilmIzle.kt` yılı tüm yanıtta arıyor. Esnek seçici sırası, içerik kapsayıcısı ve dizi kimliği kontrolüyle sınırlandırılmalı.

## 20 sağlayıcının akış haritası

Bu tablo mevcut koddaki yöntemleri sınıflandırır; canlı başarı durumu değildir. Benzer HTML kullanımı aynı backend veya tema kullanıldığının kanıtı değildir.

| Sağlayıcı | Koddaki veri/oynatıcı yaklaşımı | Başlıca doğrulama ihtiyacı |
|---|---|---|
| HDFilmCehennemi | HTML kartlar, JSON arama, videoPlayerData/data-video ve özel script çözümü | Alternatif oynatıcılar, gerçek callback sayısı, bölüm kapsamı |
| FilmModu | HTML, CSRF arama, data-k ve eski oynatıcı alternatifleri, Vidmixi AES | Cookie/token devamlılığı, sabit Referer, sessiz çözücü dönüşü |
| Dizigom | HTML ve sezon sayfaları, dinamik JS host, __PLAYER__ JSON | Boş stream'de yanlış başarı, sabit Referer, sezon bütünlüğü |
| KültFilmler | HTML/WordPress benzeri arama, kf-srcdata JSON, Vidpapi POST | Kart kökünde href bulunmaması, iframe ve video ayrımı |
| DiziBOX | HTML, dwls_search AJAX, king.php/MolyStream ve iframe | Sorgu kodlama, oynatıcı yanıtının gerçekten HLS olması |
| AnimeciX | JSON API, batch episodes/videos, bootstrap/XSRF, Tau | API şeması, token kodlama, cookie oturumu, sabit original_url |
| ÇizgiMax | HTML, suggest JSON, Base64 servers, resolve API, Tau | İçiçe iframe bağlamı, gerçek video üretimi |
| DiziFilmizle | HTML ve JSON arama, Vidmixi bePlayer AES, iframe | Çözücü başarısının callback ile ölçülmesi |
| BelgeselX | HTML, diziGetir parametreleri, video/data PHP | Tek belgeselde default kimliği, virgülle taşınan veri, iframe başarısı |
| DiziGecesi | HTML kart/bölüm, data-embed AJAX | Yol segmentinde sorgu kodlama, AJAX yanıt biçimi |
| DiziPal | WebView destekli HTML, PBKDF2/AES, openPlayer | Sabit şifreleme parametreleri ve regex format bağımlılığı |
| FullHDFilmizlesene | HTML, scx token, ROT13/Base64, Rapidvid | Token/obfuscation sürümleri, sabit oynatıcı başlıkları |
| FilmKovası | HTML, alternatif sayfa/oynatıcı ve iframe | Alternatif sayfaların kapsamı, geniş kart seçiciler |
| HDFilmDelisi | HTML ve arama alternatifleri, Vixolity script çözümü | Obfuscation formatı, regex ve yayın türü |
| RareFilmm | WordPress benzeri HTML ve iframe | Göreli iframe'ler, yeniden yükleme/eksik video ayrımı |
| DiziYou | HTML kartlar, sezon sayısından URL üretimi, iframe/özel host | Gerçek bölüm listesi, eksik/özel bölümler |
| İnatTV | Sabit kanal tanımları, maç API'si, domain API ve CDN | Ayrı API adresleri, eski CDN fallback'i, yayın içeriği |
| Patronspor | Kanal/maç/domain JSON API'leri ve CDN | Ayrı API adresleri, göreli logo, eski CDN fallback'i |
| SetFilmIzle | HTML, post-id/nonce AJAX, Setplay/Fastplay özel akışı | Nonce oturumu, imzalı manifest, tüm HTML'den yıl seçilmesi |
| SezonlukDizi | HTML ve bölüm sayfası, alternatif/embed POST | AJAX şeması, bölüm listesi tamlığı, iframe bağlamı |

## Önerilen ortak motor

### Katmanlar

1. **İstek ve oturum:** sağlayıcıya ait cookie oturumu; doğru Referer/Origin; TLS; zaman aşımı; sınırlı yeniden deneme. POST/token işlemlerini körlemesine tekrar etmemeli. Ağ hatası, challenge sayfası, şema hatası ve gerçekten boş sonuç ayrı durumlar olmalı. Loglarda token/cookie ve imzalı URL parametreleri maskelenmeli.
2. **Site profili:** ana site ve ayrı API origin'leri, liste/arama yolları, desteklenen veri formatları, içerik kapsayıcıları ve sınırlı seçici alternatifleri. Site tipi bir kelime veya tek CSS sınıfıyla seçilmemeli; API yanıt şekli, HTML işaretleri ve route birlikte değerlendirilmelidir.
3. **Adaptörler:** HTML katalog, AJAX katalog, JSON API, sezon/bölüm ve canlı kanal adaptörleri. Siteye özel nonce, kimlik ve şifreleme protokolleri gerektiğinde özel kod olarak kalır. Her WordPress sitesi aynı arama veya video sözleşmesini kullanmaz.
4. **Ortak veri modeli:** içerik kimliği, film/dizi/bölüm/canlı türü, başlık, poster, yıl, açıklama; bölümde dizi kimliği, sezon, bölüm numarası ve gerçek kaynak URL'si. Her alanın geldiği kaynak ve doğrulama durumu tutulur.
5. **Medya çözücü kayıt sistemi:** doğrudan MP4/HLS/DASH, desteklenen iframe hostları ve özel player protokolleri ayrı çözücüler olur. İçiçe iframe takibi derinlik, ziyaret edilen URL ve istek bütçesiyle sınırlanır. Metadata kazıyıcısı şifreli video protokolünü üstlenmez.
6. **Sonuç doğrulama:** iframe sayfası video sayılmaz. Kayıtlar tekrarsız olmalı; film/dizi ayrımı ve bölüm ilişkisi doğrulanmalı. Canlı smoke testte manifest içeriği ve uygun bir medya isteği sınanmalı. İmzalı URL'ler değiştirilmeden, gerekli başlıklarla korunmalı.

### Veri seçme sırası

Siteye özgü doğrulanmış API/embedded JSON; varsa uygun JSON-LD; OpenGraph ile metadata tamamlama; sonra sınırlandırılmış HTML seçicileri. Genel fallback'in bulduğu ilk videoyu otomatik olarak ana içerik kabul etmek yanlış olur: fragman ve reklam ayrımı gerekir. JSON-LD metadata desteği video URL'sini çözme garantisi vermez.

`VideoObject.contentUrl` medya içeriğini, `embedUrl` oynatıcıyı ifade eder; birbirinin yerine kullanılmamalıdır. Kaynak: https://schema.org/VideoObject

Jsoup seçicileri yapısal ve attribute tabanlı alternatifleri destekler; seçicilerin çalışması, alanın anlamının doğrulandığı anlamına gelmez. Kaynak: https://jsoup.org/cookbook/extracting-data/selector-syntax

## Değişiklik dayanıklılığı

| Değişiklik | Beklenen davranış |
|---|---|
| Yalnızca alan adı | Rolüne uygun adres kataloğu güncellemesi; path ve query korunarak bağlama göre URL çözümü |
| CSS sınıfı veya kart yapısı | Bilinen seçici alternatifi ya da structured data ile devam; kimlik kontrolü |
| JSON alanı değişmesi | Desteklenen şema sürümüne geçiş; uyumsuzsa açık şema hatası |
| Arama/bölüm listesinin AJAX'a taşınması | İlgili site adaptörünün güncellenmesi |
| Yeni nonce veya oturum akışı | İstek/oturum ve adaptör güncellemesi |
| Oynatıcı şifreleme/API değişmesi | Oynatıcı çözücüsünün güncellenmesi |
| Bilinmeyen model | Yanlış veri uydurmak yerine desteklenmeyen format hatası |

## Test ve geçiş planı

1. Önce davranış sözleşmeleri: callback üretmeyen çözücü başarısızdır; boş stream başarısızdır; altyazı tek başına video başarısı değildir. URL ve sorgu kodlama, göreli iframe ve boş alan vakaları gerçek ortak Kotlin işlevleri üzerinden test edilir.
2. Her sağlayıcının gerçek yanıtından hassas bilgiler temizlenmiş fixture'lar: liste, arama, detay, sezon/bölüm, embed/API yanıtı. Fixture'ın kaynağı ve tarihi kaydedilir. Uydurma HTML gerçek siteyle uyumluluğun kanıtı sayılmaz.
3. Aynı sözleşme testleri tüm adaptörlerde çalışır: film/dizi/anime/belgesel/canlı, Türkçe sorgu, sonuçsuz sorgu, ikinci sayfa, iki sezon, özel bölüm, göreli URL, poster/başlık eksikliği, birden çok player, altyazı ve kalite.
4. Yeni motor önce 2 farklı akışta denenir: bir HTML katalog ve bir JSON API. Eski-yeni sonuçlar karşılaştırılır; diğerleri tek tek geçirilir. 20 sağlayıcı bir kerede genel fallback'e taşınmaz.
5. Canlı kontrollerde her sağlayıcı için ana sayfa → bilinen sorgu → doğru içerik kimliği → detay → gerekiyorsa bölüm → çözülen medya → manifest/medya kontrolü raporlanır. Tek bir örnek bütün kataloğun çalıştığını kanıtlamaz; içerik türüne göre temsilci örnekler seçilir. Süresi biten linkler kalıcı fixture'a sabitlenmez.
6. Rapor her aşamayı `başarılı`, `başarısız`, `ağ/erişim engeli` veya `çalıştırılmadı` olarak ayırır. CI yalnızca ana sayfada 200 gördüğü için aktif etiketi basmaz.

Bu rapor uygulamadan önceki incelemeyi kaydeder. Ortak çekirdeğin uygulanan hali ve kalan canlı doğrulama adımları `docs/scraper-handoff.md` içindedir. Öncelik yanlış başarı bildirimi, adres rollerinin ayrılması, sorgu/URL bağlamı ve gerçek sağlayıcı fixture'larıdır.
