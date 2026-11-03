# Ortak kazıyıcı çekirdeği — sürüm 25

## Uygulanan yapı

20 sağlayıcı `AdaptiveProvider` üzerinden çalışır; İnatTV ve Patronspor ayrıca `LiveProvider` kullanır. Siteye özel JSON, nonce, CSRF, şifreleme ve oynatıcı protokolleri korunmuştur.

- Ortak sınır, callback ile üretilen HTTP(S) medya bağlantılarını ve altyazıları tekilleştirir. İç çözücünün döndürdüğü boolean başarı kanıtı sayılmaz. Bu kontrol oynatma veya manifestin erişilebilirliğinin kanıtı değildir.
- Özel çözücü medya üretmezse ana siteye ait sayfada HTML medya fallback'i denenir. BelgeselX'in taşınan sayfa URL'si ayrıca desteklenir. AnimeciX'in üç parçalı bölüm kimliği URL sanılmaz.
- Standart `video/source`, altyazı track'leri ve JSON-LD `VideoObject` desteklenir. Video `contentUrl` ile oynatıcı `embedUrl` ayrılır. Kaynak sorguları değiştirilmez. JSON-LD katalog/öneri listelerinden oynatma kaynağı seçilmez; video nesnesinin sayfa ilişkisi korunur. İç iframe'ler kendi son yanıt URL'lerine göre çözülür. Fallback gezinmesi en fazla 2 derinlik ve 6 düğümdür; harici Cloudstream host çözücülerinin iç istekleri bu bütçenin dışındadır.
- Bilinen HTML arama profillerinde boş sonuç için JSON-LD katalog alternatifi vardır. JSON API sağlayıcılarının sözleşmeleri kendi adaptörlerinde kalır. İlk katalog sayfası boşsa uygun URL'de semantic katalog denenir; sonraki sayfalar tahmin edilmez.
- 17 HTML sağlayıcının detay yanıtları ortak JSON-LD/OpenGraph metadata tamamlaması kullanır. Siteye özel mevcut alanlar korunur. Bilinen dizi metadata'sı film gibi sunulmaz; boş bölüm listesi açık hata verir.
- Arama parametresi ve yol segmenti farklı kodlanır. Poster ve göreli iframe URL çözümü ortaktır. FilmModu/Dizigom Referer başlıkları güncel mainUrl'den gelir. AnimeciX bootstrap origin ve XSRF cookie kodlaması düzeltilmiştir.
- DiziYou bölüm sayısından URL uydurmaz; sayfa/semantik kayıtlardaki gerçek bölüm adreslerini ve varsa açık sezon sayfası adreslerini kullanır. Site yalnızca sayı veriyorsa artık açık hata verir; yeni API sözleşmesi gerektiği anlaşılır.
- İnatTV/Patronspor API origin'i `apiUrl` olarak ayrıdır. `domains.json` içindeki `api_url` alanı güncelleyici tarafından Kotlin'e eşitlenir. Bu alanın sahipliği ve API uyumluluğu insan tarafından doğrulanmalıdır; HTML ana sayfa doğrulaması API sözleşmesini test etmez. Eski sabit CDN kaldırılmıştır; domain API'si veya sayfadan alınan güncel base kullanılır ve 5 dakika önbelleklenir.
- Uyarlayıcı hataları token/URL içermeyen `TurkProviders` log etiketiyle aşama ve exception türü olarak raporlanır. İptal hataları yutulmaz. Sabit 1080p atamaları bilinmeyen kaliteye çevrilmiştir; kaynağın bildirdiği kalite korunur.

## Kullanıcı test listesi

Her sağlayıcıda uygun içerik türleri için şu zinciri dene:

1. Ana sayfa ve ikinci sayfa: farklı içerik, geçerli başlık/poster, yinelenmeyen kartlar.
2. Arama: bilinen isim; Türkçe karakter; `&`, `+`, `#` içeren sorgu; hiç sonuç vermeyen sorgu. Liste öneri/reklam içermemeli.
3. Film detay: doğru başlık, yıl, açıklama ve poster.
4. Dizi/anime/belgesel: en az iki sezon; ilk/son bölüm; varsa özel bölüm. Bölümler başka diziye ait olmamalı.
5. Oynatma: ilk ve alternatif sunucu; boş stream; kırık iframe; altyazı; gerçek kalite ve dil. İframe sayfasının bulunması tek başına başarı değildir.
6. Canlı: kanal ve maç listesi; API kapalı; domain API kapalı; doğrudan m3u8; URL kodlu `id`; süresi dolmuş yayın.
7. Hata durumunda sağlayıcı, aşama, içerik adresi (imzalı tokenları silerek) ve Cloudstream logunu kaydet.

Öncelikli sağlayıcılar: DiziYou (bölüm kaynağı değişti), İnatTV/Patronspor (güncel domain API zorunlu), BelgeselX (tek belgesel fallback), FilmModu/AnimeciX (oturum/token), DiziBOX (HTML player ile HLS ayrımı).

## Doğrulama durumu

Kotlin kaynakları yerel önbellekteki Cloudstream/Android bağımlılıklarıyla derleme kontrolünden geçirildi. Eski API kurucuları ve Java nullable değerleri için derleyici uyarıları mevcut. Bu, tam Gradle paketleme veya .cs3 kurulum testi değildir. Kullanıcının isteğiyle otomatik testler, canlı site kontrolleri ve oynatma testleri bu uygulama turunda çalıştırılmadı. Önceki Python test sonucu yeni kazıyıcı davranışını doğrulamaz.

Bilinmeyen API, yeni şifreleme veya tamamen farklı site modeli kendiliğinden çözülemez; ilgili adaptör güncellenir. Sahte veri üretmek yerine açık hata/boş sonuç raporlanır. Gerçek site fixture'ları ve cihaz testleri henüz yoktur.
