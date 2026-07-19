package com.philhome.tradingclaudegod

/**
 * Idées d'investissement classées par NIVEAU DE RISQUE — but pédagogique (comprendre le risque),
 * PAS des conseils personnalisés. Le classement suit des critères objectifs (type d'actif +
 * volatilité mesurée dans l'app), pas une opinion. « Presque sans risque » n'existe pas en bourse :
 * on parle de risque PLUS FAIBLE, jamais nul.
 */
enum class Tier(val titre: String, val intro: String) {
    FAIBLE(
        "🟢  Risque plus faible (les fondations)",
        "Des paniers très diversifiés (ETF) : ton argent est réparti sur des centaines d'entreprises. " +
            "Si l'une chute, les autres amortissent. C'est la base recommandée pour débuter. " +
            "Ça peut quand même baisser lors d'une crise générale — mais historiquement, ça remonte sur le long terme."
    ),
    MOYEN(
        "🟡  Risque moyen (à surveiller)",
        "De grandes entreprises solides, mais une SEULE société à la fois. Si elle a un souci (résultats " +
            "décevants, scandale, secteur en difficulté), son cours peut chuter de 20 à 40 % même si elle est " +
            "réputée. À ne pas mettre en trop grosse part, et à suivre régulièrement."
    ),
    ELEVE(
        "🔴  Risque élevé (spéculatif)",
        "Très volatil : peut faire +50 % ou −50 % en peu de temps. Crypto et actions « à la mode » sont ici. " +
            "On n'y met qu'une TRÈS petite part qu'on est prêt à perdre entièrement. Ce n'est pas une base pour débuter."
    )
}

data class Idea(val name: String, val symbol: String, val kind: String, val tier: Tier, val why: String)

object Ideas {
    val ALL = listOf(
        // --- Risque plus faible : ETF larges ---
        Idea("ETF Monde (MSCI World)", "CW8.PA", "ETF large", Tier.FAIBLE,
            "Un seul produit = des centaines des plus grandes entreprises mondiales. La brique de base d'un patrimoine."),
        Idea("ETF S&P 500 (USA)", "ESE.PA", "ETF large", Tier.FAIBLE,
            "Les 500 plus grandes entreprises américaines en un clic. Très diversifié, moteur historique des marchés."),
        Idea("ETF Monde (iShares)", "IWDA.AS", "ETF large", Tier.FAIBLE,
            "Même principe « monde entier », autre émetteur. Montre qu'on peut choisir son fournisseur d'ETF."),

        // --- Risque moyen : grandes actions solides ---
        Idea("LVMH", "MC.PA", "Action France", Tier.MOYEN,
            "Leader mondial du luxe, très solide — mais action unique, sensible à la consommation (Chine notamment)."),
        Idea("Air Liquide", "AI.PA", "Action France", Tier.MOYEN,
            "Gaz industriels, activité stable et dividende régulier. Défensive, mais reste une seule société."),
        Idea("Apple", "AAPL", "Action US", Tier.MOYEN,
            "Marque ultra-puissante et trésorerie énorme — mais dépend de l'iPhone et des tensions US/Chine."),
        Idea("Microsoft", "MSFT", "Action US", Tier.MOYEN,
            "Logiciels + cloud + IA, revenus très récurrents. Solide, mais valorisation élevée = attentes fortes."),

        // --- Risque élevé : spéculatif / très volatil ---
        Idea("Bitcoin", "BTC-EUR", "Crypto", Tier.ELEVE,
            "La crypto reine, mais sans revenus ni bilan : son prix dépend de l'offre/demande. Peut fondre de moitié."),
        Idea("Ethereum", "ETH-EUR", "Crypto", Tier.ELEVE,
            "Crypto n°2, plus « utile » (contrats) mais tout aussi volatile. Petite part seulement."),
        Idea("Tesla", "TSLA", "Action US", Tier.ELEVE,
            "Voitures électriques + paris technos. Action très volatile, portée par l'émotion autant que les chiffres."),
        Idea("Nvidia", "NVDA", "Action US", Tier.ELEVE,
            "Puces pour l'IA, croissance spectaculaire — donc valorisation extrême et gros écarts de cours possibles."),
    )

    fun byTier(t: Tier) = ALL.filter { it.tier == t }
}
