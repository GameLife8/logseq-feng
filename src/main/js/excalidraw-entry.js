/**
 * Excalidraw webpack entry point.
 * This file is bundled by webpack into static/js/excalidraw-bundle.js
 * and exposes ExcalidrawLib as a window global, similar to how React is exposed.
 *
 * Usage in ClojureScript (via shadow-cljs global resolve):
 *   js/ExcalidrawLib.Excalidraw
 */

// Re-export everything from @excalidraw/excalidraw
export * from '@excalidraw/excalidraw';
