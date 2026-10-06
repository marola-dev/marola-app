package marola.oods

class MainSpec extends munit.FunSuite:

  test("no arguments: exit 2, usage on stderr") {
    assertEquals(Main.run(Nil), (2, Main.usage))
  }

  test("oods frobnicate: exit 2, the unknown command and usage on stderr") {
    val (code, stderr) = Main.run(Seq("frobnicate"))
    assertEquals(code, 2)
    assert(stderr.startsWith("oods: unknown command 'frobnicate'\n"), stderr)
    assert(stderr.endsWith(Main.usage), stderr)
  }

  test("the usage lists every command") {
    Main.Commands.foreach(cmd => assert(Main.usage.contains(s"  oods $cmd"), cmd))
  }

  test("a known command is not implemented yet: exit 2") {
    assertEquals(Main.run(Seq("check")), (2, "oods check: not implemented yet\n"))
  }
