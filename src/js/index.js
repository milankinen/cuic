import scrollIntoView from "smooth-scroll-into-view-if-needed"

window.__CUIC__ = {
  scrollIntoView: async node => {
    await scrollIntoView(node, {
      scrollMode: "if-needed",
      block: "center",
      inline: "nearest",
    })
  },
  isInViewport: node => {
    const rect = node.getBoundingClientRect();
    return (
      rect.top >= 0 &&
      rect.left >= 0 &&
      rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&
      rect.right <= (window.innerWidth || document.documentElement.clientWidth)
    );
  }
}
