# Sağlayıcı ortak çekirdeği uygulama planı

**Amaç:** Mevcut 20 site adaptörünü koruyarak ortak URL, metadata ve medya sonuç doğrulamasını uygulamak.
**Mimari:** Ortak MainAPI tabanı video callback'lerini doğrular ve yinelenenleri eler. Siteye özel çözümleme önce çalışır; sonuç yoksa sınırlı HTML/JSON-LD medya adaptörü çalışır. Bilinmeyen şifreleme protokolleri tahmin edilmez.
**Teknoloji:** Kotlin, mevcut Cloudstream/NiceHttp/Jsoup/Jackson bağımlılıkları.
**Referans:** docs/scraper-audit.md
**Kısıt:** Canlı ve oynatma testlerini kullanıcı yapacak; bu tur test çalıştırılmayacak. Yalnızca Kotlin derleme kontrolü yapılacak. Dizin Git deposu değil; worktree/commit yok.

- [x] Ortak URL, sorgu ve metadata yardımcıları; tür ayrımını ve göreli URL bağlamını koru.
- [x] Ortak medya motoru: callback doğrulama, tekilleştirme, aşama logları, sınırlı iframe fallback, JSON-LD ve doğrudan medya.
- [x] 20 sağlayıcıyı motor tabanına geçir; mevcut özel çözücüleri koru.
- [x] Sorgu kodlama, sabit başlıklar, API adres rolleri ve bölüm tahminini düzelt.
- [x] Sürüm ve kullanıcı kontrol listesini güncelle; değişikliklerin kapsamını statik olarak gözden geçir.
