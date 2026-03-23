/**
 * simple-mind-map webpack entry point.
 * Bundled into static/js/mind-map-bundle.js and exposed as window.SimpleMindMap.
 *
 * Usage in ClojureScript:
 *   (def MindMap (.-default js/SimpleMindMap))
 *   (new MindMap #js {:el container-el :data root-data})
 */

import MindMap from 'simple-mind-map';

window.SimpleMindMap = MindMap;
