import 'package:flutter/material.dart';
import 'dart:collection';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'UrbanNav',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const MyHomePage(title: 'Welcome to UrbanNav!'),
    );
  }
}

// ──────────────────────────────────────────────
// RSSI Filtering Pipeline
// Raw RSSI → [Median Filter] → [EMA Filter] → Filtered RSSI → Distance
// ──────────────────────────────────────────────

/// Median Filter: smooths out spikes/noise from raw RSSI readings.
/// Maintains a sliding window of recent values and returns the median.
class MedianFilter {
  final int windowSize;
  final Queue<int> _window = Queue<int>();

  MedianFilter({this.windowSize = 5});

  /// Adds a raw RSSI value and returns the median of the current window.
  int filter(int rawRssi) {
    _window.addLast(rawRssi);
    if (_window.length > windowSize) {
      _window.removeFirst();
    }

    final sorted = _window.toList()..sort();
    final mid = sorted.length ~/ 2;

    if (sorted.length % 2 == 0) {
      return ((sorted[mid - 1] + sorted[mid]) / 2).round();
    } else {
      return sorted[mid];
    }
  }

  void reset() {
    _window.clear();
  }
}

/// EMA (Exponential Moving Average) Filter: applies weighted smoothing.
/// Recent values have more influence controlled by the alpha parameter.
class EmaFilter {
  final double alpha;
  double? _previousEma;

  /// [alpha] controls smoothing: closer to 1.0 = less smoothing (more reactive),
  /// closer to 0.0 = more smoothing (more stable).
  EmaFilter({this.alpha = 0.3});

  /// Applies EMA to the given value (typically the output of the Median Filter).
  double filter(int medianRssi) {
    if (_previousEma == null) {
      _previousEma = medianRssi.toDouble();
    } else {
      _previousEma = alpha * medianRssi + (1 - alpha) * _previousEma!;
    }
    return _previousEma!;
  }

  void reset() {
    _previousEma = null;
  }
}

/// Combines Median and EMA filters into a single pipeline, and converts
/// the filtered RSSI to an estimated distance.
class RssiFilterPipeline {
  final MedianFilter _medianFilter;
  final EmaFilter _emaFilter;
  final int txPower;
  final double pathLossExponent;

  RssiFilterPipeline({
    int medianWindowSize = 5,
    double emaAlpha = 0.3,
    this.txPower = -59, /// the RSSI value at 1 meter from beacon
    this.pathLossExponent = 2,
  })  : _medianFilter = MedianFilter(windowSize: medianWindowSize),
        _emaFilter = EmaFilter(alpha: emaAlpha);

  /// Returns a record containing the filtered RSSI and estimated distance.
  ({double filteredRssi, double distanceMeters}) process(int rawRssi) {
    /// take median
    final medianRssi = _medianFilter.filter(rawRssi);

    /// weighted filter
    final filteredRssi = _emaFilter.filter(medianRssi);

    /// find distance
    final distanceMeters = _rssiToDistance(filteredRssi);

    return (filteredRssi: filteredRssi, distanceMeters: distanceMeters);
  }

  double _rssiToDistance(double rssi) {
    return Math.pow(10, (txPower - rssi) / (10 * pathLossExponent)).toDouble();
  }

  void reset() {
    _medianFilter.reset();
    _emaFilter.reset();
  }
}

// ──────────────────────────────────────────────
// Math helper (to avoid importing dart:math everywhere)
// ──────────────────────────────────────────────
class Math {
  static num pow(num base, num exponent) {
    return base is int && exponent is int
        ? _intPow(base, exponent)
        : double.parse(
      (base.toDouble()).toString(),
    );
  }

  static double _doubleToDouble(double base, double exponent) {
    return double.parse(
      (base).toString(),
    );
  }

  static int _intPow(int base, int exponent) {
    int result = 1;
    for (int i = 0; i < exponent; i++) {
      result *= base;
    }
    return result;
  }
}

// ──────────────────────────────────────────────
// UI (unchanged)
// ──────────────────────────────────────────────

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  // RSSI filter pipeline ready for future beacon integration
  final RssiFilterPipeline _rssiPipeline = RssiFilterPipeline(
    medianWindowSize: 5, // Number of recent RSSI readings for median
    emaAlpha: 0.3,       // EMA weight: 0.3 = moderate smoothing
    txPower: -59,        // Calibrated RSSI at 1 meter (adjust per beacon)
    pathLossExponent: 2.5, // Indoor environment factor
  );

  // Example: When beacon signals are received in the future, call this:
  // final result = _rssiPipeline.process(rawRssiValue);
  // result.filteredRssi  → smoothed RSSI
  // result.distanceMeters → estimated distance to beacon

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        centerTitle: true,
        title: Text(widget.title),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              'Welcome to UrbanNav!',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 16),
            Text(
              'There are 0 beacons available nearby',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ],
        ),
      ),
    );
  }
}