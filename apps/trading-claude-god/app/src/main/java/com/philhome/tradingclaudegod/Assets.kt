package com.philhome.tradingclaudegod

/**
 * Une valeur suivie. [symbol] = ticker Yahoo Finance (ex. "MC.PA" = LVMH Paris, "BTC-EUR", "^FCHI").
 * [kind] = catégorie lisible pour l'utilisateur.
 */
data class Asset(val name: String, val symbol: String, val kind: String)

/**
 * Sélection « valeurs solides » par défaut, diversifiée : grandes actions FR/US, ETF larges,
 * crypto majeures, indices (humeur du marché). Modifiable plus tard par l'utilisateur.
 */
object Assets {
    val DEFAULT = listOf(
        Asset("LVMH", "MC.PA", "Action France"),
        Asset("Air Liquide", "AI.PA", "Action France"),
        Asset("TotalEnergies", "TTE.PA", "Action France"),
        Asset("Apple", "AAPL", "Action US"),
        Asset("Microsoft", "MSFT", "Action US"),
        Asset("ETF Monde (MSCI World)", "CW8.PA", "ETF large"),
        Asset("Bitcoin", "BTC-EUR", "Crypto"),
        Asset("Ethereum", "ETH-EUR", "Crypto"),
        Asset("CAC 40", "^FCHI", "Indice"),
        Asset("S&P 500", "^GSPC", "Indice"),
    )
}
