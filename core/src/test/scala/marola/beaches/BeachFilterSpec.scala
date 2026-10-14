package marola.beaches

/** Names taken from live Overpass answers for the three site areas, 2026-10-11. */
class BeachFilterSpec extends munit.FunSuite:

  test("Rio's playgrounds, clubs, bare 'praia' and the Lagoa condo are not beaches") {
    List(
      "parquinho",
      "praia",
      "S2",
      "Cossetti's Playground",
      "Cossetti's Playgroung",
      "CLUBE DE CANOA POLINÉSIA SOUL VA'A",
      "Paradiso Club",
      "Point Cap Ferrat",
      "Beach"
    ).foreach(n => assert(BeachFinder.isJunkName(n), n))
  }

  test("real beaches stay, including ones without 'Praia' in the name") {
    List(
      "Praia de Copacabana",
      "Prainha",
      "Praia do Diabo",
      "Jurerê",
      "Ponta do Capim",
      "Mar Grande",
      "Ilha dos Papagaios"
    ).foreach(n => assert(!BeachFinder.isJunkName(n), n))
  }
