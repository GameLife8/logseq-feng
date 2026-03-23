const path = require('path');
const webpack = require('webpack');

var config = {
  mode: "development",
  externals: {
    'react': 'React',
    'react-dom': 'ReactDOM',
  },
  module: {
    rules: [
      {
        // docs: https://webpack.js.org/configuration/module/#resolvefullyspecified
        test: /\.m?js/,
        resolve: {
          fullySpecified: false,
        }
      }
    ]
  },
  plugins: [
    // fix "process is not defined" error:
    new webpack.ProvidePlugin({
      process: 'process/browser',
    }),
  ],
};

var AppConfig = Object.assign({}, config, {
  name: "app",
  entry: {
    "db-worker" : "./target/db-worker.js",
    "inference-worker" : "./target/inference-worker.js"
  },

  output: {
    path: path.resolve(__dirname, 'static/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

var MobileConfig = Object.assign({}, config, {
  name: "mobile",
  entry: {
    "db-worker" : "./target/db-worker.js",
  },

  output: {
    path: path.resolve(__dirname, 'static/mobile/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

// Excalidraw is bundled separately and exposed as window.ExcalidrawLib,
// matching how React/ReactDOM are exposed as globals.
// shadow-cljs resolves "@excalidraw/excalidraw" to this global.
var ExcalidrawConfig = {
  name: "excalidraw",
  mode: "production",
  entry: "./src/main/js/excalidraw-entry.js",
  externals: {
    'react': 'React',
    'react-dom': 'ReactDOM',
  },
  module: {
    rules: [
      {
        test: /\.m?js/,
        resolve: { fullySpecified: false },
      },
      {
        // Ignore CSS imports inside excalidraw – CSS is copied separately by gulp
        test: /\.css$/,
        type: 'asset/source',
      },
      {
        // Font files – inline as base64 (avoids path issues)
        test: /\.(woff|woff2|ttf|eot|otf)$/,
        type: 'asset/inline',
      },
    ]
  },
  output: {
    path: path.resolve(__dirname, 'static/js'),
    filename: 'excalidraw-bundle.js',
    chunkFilename: 'excalidraw-chunk-[id].js',
    library: {
      name: 'ExcalidrawLib',
      type: 'window',
    },
    publicPath: '/static/js/',
    clean: false,
  },
};

// simple-mind-map bundled separately, exposed as window.SimpleMindMap
var SimpleMindMapConfig = {
  name: "mind-map",
  mode: "production",
  entry: "./src/main/js/mind-map-entry.js",
  externals: {
    'react': 'React',
    'react-dom': 'ReactDOM',
  },
  module: {
    rules: [
      {
        test: /\.m?js/,
        resolve: { fullySpecified: false },
      },
      {
        test: /\.css$/,
        type: 'asset/source',
      },
      {
        test: /\.(woff|woff2|ttf|eot|otf|svg|png)$/,
        type: 'asset/inline',
      },
    ]
  },
  output: {
    path: path.resolve(__dirname, 'static/js'),
    filename: 'mind-map-bundle.js',
    library: {
      name: 'SimpleMindMap',
      type: 'window',
    },
    publicPath: '/static/js/',
    clean: false,
  },
};

module.exports = [
  AppConfig, MobileConfig, ExcalidrawConfig, SimpleMindMapConfig,
];
