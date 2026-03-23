/**
 * simple-mind-map webpack entry point.
 * Bundled into static/js/mind-map-bundle.js and exposed as window.SimpleMindMap.
 *
 * Usage in ClojureScript:
 *   (def MindMap (.-default js/SimpleMindMap))
 *   (new MindMap #js {:el container-el :data root-data})
 */

// Re-export default so webpack library wrapper sets:
//   window.SimpleMindMap = { default: MindMap }
// ClojureScript resolves it via (.-default js/SimpleMindMap).
export { default } from 'simple-mind-map';
