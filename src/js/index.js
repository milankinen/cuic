import scrollIntoView from "smooth-scroll-into-view-if-needed"

window.__CUIC__ = {
  scrollIntoView: async node => {
    await scrollIntoView(node, {
      scrollMode: "if-needed",
      block: "center",
      inline: "nearest",
    })
  }
}
