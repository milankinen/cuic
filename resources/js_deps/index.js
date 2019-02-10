var deps = window.__cuic_deps = {};
deps.scrollIntoView = require("smooth-scroll-into-view-if-needed").default;

if (!window.__cuic_clicks) {
  var clicks = window.__cuic_clicks = new Set();
  document.addEventListener("click", function (event) {
    clicks.add(event.target);
  }, true);
}
