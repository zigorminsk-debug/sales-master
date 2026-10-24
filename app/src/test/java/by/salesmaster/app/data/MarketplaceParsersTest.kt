package by.salesmaster.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceParsersTest {

    @Test
    fun `wildberries parses v4 card response with top-level products and nested sizes price`() {
        val rawJson = """
            {
              "products": [
                {
                  "id": 273059631,
                  "brand": "Xiaomi",
                  "name": "Робот-пылесос Robot Vacuum S10",
                  "sizes": [
                    {
                      "name": "0",
                      "price": {
                        "basic": 2599000,
                        "product": 1499000,
                        "total": 1499000
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val offers = WildberriesClient.parseProductsFromJson(rawJson)
        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals(Marketplace.WILDBERRIES, offer.marketplace)
        assertEquals("273059631", offer.externalId)
        assertEquals("Xiaomi Робот-пылесос Robot Vacuum S10", offer.title)
        assertEquals(14990.0, offer.price, 0.01)
        assertEquals("https://www.wildberries.ru/catalog/273059631/detail.aspx", offer.productUrl)
    }

    @Test
    fun `wildberries parses basket CDN card json and price history`() {
        val cardJson = """
            {
              "imt_id": 204918231,
              "imt_name": "Наушники беспроводные Bluetooth",
              "subj_name": "Наушники",
              "selling": {
                "brand_name": "JBL"
              }
            }
        """.trimIndent()

        val priceHistoryJson = """
            [
              {"dt": 1720000000, "price": {"RUB": 399000}},
              {"dt": 1725000000, "price": {"RUB": 329000}}
            ]
        """.trimIndent()

        val offer = WildberriesClient.parseBasketCardAndPrice(
            nmId = "164379765",
            cardJson = cardJson,
            priceHistoryJson = priceHistoryJson,
        )
        assertNotNull(offer)
        assertEquals("JBL Наушники беспроводные Bluetooth", offer!!.title)
        assertEquals(3290.0, offer.price, 0.01)
        assertEquals("164379765", offer.externalId)
    }

    @Test
    fun `wildberries extracts product id from urls, shared text and article numbers`() {
        assertEquals(
            "273059631",
            WildberriesClient.extractProductId("Посмотри на WB https://www.wildberries.ru/catalog/273059631/detail.aspx?targetUrl=GP"),
        )
        assertEquals(
            "14671602",
            WildberriesClient.extractProductId("https://wildberries.by/catalog/14671602/detail.aspx"),
        )
        assertEquals(
            "1097975275",
            WildberriesClient.extractProductId("Артикул: 1097975275"),
        )
        assertEquals("basket-02.wbbasket.ru", WildberriesClient.basketHostForVol(146))
        assertEquals("basket-42.wbbasket.ru", WildberriesClient.basketHostForVol(10979))
    }

    @Test
    fun `ozon parses search results widgetStates with mainState textAtom and priceV2`() {
        val composerSearchJson = """
            {
              "widgetStates": {
                "tileGridDesktop-312456-default-1": "{\"items\":[{\"sku\":\"159283746\",\"action\":{\"link\":\"/product/robot-pylesos-xiaomi-159283746/?asb=123\"},\"mainState\":[{\"type\":\"priceV2\",\"priceV2\":{\"price\":[{\"text\":\"13 490 ₽\",\"textStyle\":\"PRICE\"},{\"text\":\"19 990 ₽\",\"textStyle\":\"ORIGINAL_PRICE\"}]}},{\"type\":\"textAtom\",\"textAtom\":{\"text\":\"Робот-пылесос Xiaomi Vacuum E10, белый\"}}]}]}"
              }
            }
        """.trimIndent()

        val offers = OzonOfferParser.parseResponse(composerSearchJson, fallbackPath = "/search/?text=робот")
        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals(Marketplace.OZON, offer.marketplace)
        assertEquals("Робот-пылесос Xiaomi Vacuum E10, белый", offer.title)
        assertEquals(13490.0, offer.price, 0.01)
        assertEquals("https://www.ozon.ru/product/robot-pylesos-xiaomi-159283746/", offer.productUrl)
        assertEquals("159283746", offer.externalId)
    }

    @Test
    fun `ozon parses single product card when webProductHeading and webPrice are separate widgets`() {
        val composerCardJson = """
            {
              "widgetStates": {
                "webProductHeading-331200-default-1": "{\"title\":\"Кофемашина DeLonghi Magnifica S ECAM 22.110.B\"}",
                "webPrice-331205-default-1": "{\"isAvailable\":true,\"price\":\"34 990 ₽\",\"cardPrice\":\"31 850 ₽\",\"originalPrice\":\"45 000 ₽\"}"
              }
            }
        """.trimIndent()

        val offers = OzonOfferParser.parseResponse(
            composerCardJson,
            fallbackPath = "/product/kofemashina-delonghi-178293456/",
        )
        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals("Кофемашина DeLonghi Magnifica S ECAM 22.110.B", offer.title)
        assertEquals(31850.0, offer.price, 0.01)
        assertEquals("https://www.ozon.ru/product/kofemashina-delonghi-178293456/", offer.productUrl)
        assertEquals("178293456", offer.externalId)
    }

    @Test
    fun `ozon extracts target path from product urls, shared messages and skus`() {
        assertEquals(
            "/product/naushniki-sony-847362519/",
            OzonClient.extractTargetPath("Отличная цена! https://www.ozon.ru/product/naushniki-sony-847362519/?avtc=1"),
        )
        assertEquals(
            "/product/847362519/",
            OzonClient.extractTargetPath("Ozon 847362519"),
        )
    }

    @Test
    fun `httpJson recognizes same marketplace across subdomains and cdn hosts`() {
        assertTrue(HttpJson.sameMarketplace("www.wildberries.ru", "search.wb.ru"))
        assertTrue(HttpJson.sameMarketplace("www.wildberries.ru", "card.wb.ru"))
        assertTrue(HttpJson.sameMarketplace("www.wildberries.ru", "basket-15.wbbasket.ru"))
        assertTrue(HttpJson.sameMarketplace("wildberries.by", "card.wb.ru"))
        assertTrue(HttpJson.sameMarketplace("www.ozon.ru", "api.ozon.ru"))
        assertTrue(HttpJson.sameMarketplace("ozon.by", "www.ozon.ru"))
        assertFalse(HttpJson.sameMarketplace("www.wildberries.ru", "www.ozon.ru"))
    }

    @Test
    fun `webBridge cleans WebView user agent markers and decodes evaluateJavascript strings`() {
        val rawUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36"
        val cleaned = MarketplaceWebBridge.cleanWebViewUserAgent(rawUa)
        assertFalse("; wv" in cleaned)
        assertFalse("Version/4.0" in cleaned)
        assertTrue("Chrome/131.0.6778.39" in cleaned)

        val encoded = "\"{\\\"state\\\":\\\"ready\\\",\\\"domOffers\\\":[]}\""
        val decoded = MarketplaceWebBridge.decodeEvaluateResult(encoded)
        assertEquals("{\"state\":\"ready\",\"domOffers\":[]}", decoded)
    }
}
