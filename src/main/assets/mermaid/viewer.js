// Renders one Mermaid diagram. The app calls renderDiagram after the page has loaded and reads the
// outcome from document.title ("ok" or "error"); there is deliberately no JavaScript bridge.
(function () {
  "use strict";
  var container = document.getElementById("diagram");
  window.renderDiagram = function (text, dark) {
    document.title = "rendering";
    container.textContent = "";
    try {
      window.mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: dark ? "dark" : "default",
        suppressErrorRendering: true,
        maxTextSize: 100000,
      });
      window.mermaid.render("pocketpi-diagram", text).then(
        function (result) {
          container.innerHTML = result.svg;
          document.title = "ok";
        },
        function () {
          document.title = "error";
        }
      );
    } catch (e) {
      document.title = "error";
    }
  };
})();
