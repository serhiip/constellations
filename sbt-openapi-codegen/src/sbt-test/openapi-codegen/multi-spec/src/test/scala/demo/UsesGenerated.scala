package demo

class UsesGenerated extends munit.FunSuite {
  test("both specs generate types in the same package") {
    val widget = demo.api.Widget(name = "bolt")
    val gadget = demo.api.Gadget(name = "cog")
    assertEquals(widget.name, "bolt")
    assertEquals(gadget.name, "cog")
  }
}
