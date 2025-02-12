import 'dart:async';
import 'dart:io';

import 'package:file/local.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;

/// Audio Recorder Plugin
class FlutterAudioRecorder {
  static const MethodChannel _methodChannel =
      const MethodChannel('flutter_audio_recorder');
  static const EventChannel _eventChannel =
      EventChannel('flutter_audio_recorder.events');
  static const String DEFAULT_EXTENSION = '.m4a';
  static LocalFileSystem fs = LocalFileSystem();

  final Completer<void> _initRecorder = Completer<void>();

  String? _path;
  String? _extension;
  Recording? _recording;
  int? _sampleRate;
  Stream<List<double>?>? _sampleStream;
  StreamSubscription? _sampleSubscription;

  Future<void> get initialized => _initRecorder.future;
  Recording? get recording => _recording;

  FlutterAudioRecorder(String path,
      {AudioFormat? audioFormat, int sampleRate = 16000}) {
    _init(path, audioFormat, sampleRate);
  }

  /// Initialized recorder instance
  Future<void> _init(
      String path, AudioFormat? audioFormat, int sampleRate) async {
    String extension;
    String extensionInPath;
    // Extension(.xyz) of Path
    extensionInPath = p.extension(path);
    // Use AudioFormat
    if (audioFormat != null) {
      // .m4a != .m4a
      if (_stringToAudioFormat(extensionInPath) != audioFormat) {
        // use AudioOutputFormat
        extension = _audioFormatToString(audioFormat);
        path = p.withoutExtension(path) + extension;
      } else {
        extension = p.extension(path);
      }
    } else {
      // Else, Use Extension that inferred from Path
      // if extension in path is valid
      if (_isValidAudioFormat(extensionInPath)) {
        extension = extensionInPath;
      } else {
        extension = DEFAULT_EXTENSION; // default value
        path += extension;
      }
    }
    File file = fs.file(path);
    if (await file.exists()) {
      throw new Exception("A file already exists at the path :" + path);
    } else if (!await file.parent.exists()) {
      throw new Exception("The specified parent directory does not exist");
    }
    _path = path;
    _extension = extension;
    _sampleRate = sampleRate;

    late Map<String, Object> response;
    var result = await _methodChannel.invokeMethod('init',
        {"path": _path, "extension": _extension, "sampleRate": _sampleRate});

    if (result != false) {
      response = Map.from(result);
    }

    _recording = new Recording()
      ..status = _stringToRecordingStatus(response['status'] as String?)
      ..metering = new AudioMetering(
          averagePower: -120, peakPower: -120, isMeteringEnabled: true);

    _initRecorder.complete();
    return;
  }

  void listenToSampleUpdate(
      Function(List<double>?) onData, Function(dynamic) onError) {
    if (_sampleStream == null) {
      _sampleStream = _eventChannel
          .receiveBroadcastStream()
          .map((buffer) => buffer as List<dynamic>?)
          .map((list) => list?.map((e) => double.parse('$e')).toList());
    }
    _sampleSubscription?.cancel();
    _sampleSubscription = _sampleStream!.handleError((error) {
      _sampleStream = null;
      onError(error);
    }).listen(onData);
  }

  /// Request an initialized recording instance to be [started]
  /// Once executed, audio recording will start working and
  /// a file will be generated in user's file system
  Future<void> start() async {
    return _methodChannel.invokeMethod('start');
  }

  /// Request currently [Recording] recording to be [Paused]
  /// Note: Use [current] to get latest state of recording after [pause]
  Future<void> pause() async {
    return _methodChannel.invokeMethod('pause');
  }

  /// Request currently [Paused] recording to continue
  Future<void> resume() async {
    return _methodChannel.invokeMethod('resume');
  }

  /// Request the recording to stop
  /// Once its stopped, the recording file will be finalized
  /// and will not be start, resume, pause anymore.
  Future<Recording?> stop() async {
    Map<String, Object> response;
    var result = await _methodChannel.invokeMethod('stop');

    if (result != null) {
      response = Map.from(result);
      _responseToRecording(response);
    }

    _sampleSubscription?.cancel();

    return _recording;
  }

  /// Ask for current status of recording
  /// Returns the result of current recording status
  /// Metering level, Duration, Status...
  Future<Recording?> current({int channel = 0}) async {
    Map<String, Object> response;

    var result =
        await _methodChannel.invokeMethod('current', {"channel": channel});

    if (result != null && _recording?.status != RecordingStatus.Stopped) {
      response = Map.from(result);
      _responseToRecording(response);
    }

    return _recording;
  }

  /// Returns the result of record permission
  /// if not determined(app first launch),
  /// this will ask user to whether grant the permission
  static Future<bool?> get hasPermissions async {
    bool? hasPermission = await _methodChannel.invokeMethod('hasPermissions');
    return hasPermission;
  }

  ///  util - response msg to recording object.
  void _responseToRecording(Map<String, Object> response) {
    _recording!.duration =
        new Duration(milliseconds: response['duration'] as int);
    _recording!.path = response['path'] as String?;
    _recording!.audioFormat =
        _stringToAudioFormat(response['audioFormat'] as String?);
    _recording!.extension = response['audioFormat'] as String?;
    _recording!.metering = new AudioMetering(
        peakPower: response['peakPower'] as double?,
        averagePower: response['averagePower'] as double?,
        isMeteringEnabled: response['isMeteringEnabled'] as bool?);
    _recording!.status =
        _stringToRecordingStatus(response['status'] as String?);
  }

  /// util - verify if extension string is supported
  static bool _isValidAudioFormat(String extension) {
    switch (extension) {
      case ".wav":
      case ".mp4":
      case ".aac":
      case ".m4a":
        return true;
      default:
        return false;
    }
  }

  /// util - Convert String to Enum
  static AudioFormat? _stringToAudioFormat(String? extension) {
    switch (extension) {
      case ".wav":
        return AudioFormat.WAV;
      case ".mp4":
      case ".aac":
      case ".m4a":
        return AudioFormat.AAC;
      default:
        return null;
    }
  }

  /// Convert Enum to String
  static String _audioFormatToString(AudioFormat format) {
    switch (format) {
      case AudioFormat.WAV:
        return ".wav";
      case AudioFormat.AAC:
        return ".m4a";
      default:
        return ".m4a";
    }
  }

  /// util - Convert String to Enum
  static RecordingStatus _stringToRecordingStatus(String? status) {
    switch (status) {
      case "unset":
        return RecordingStatus.Unset;
      case "initialized":
        return RecordingStatus.Initialized;
      case "recording":
        return RecordingStatus.Recording;
      case "paused":
        return RecordingStatus.Paused;
      case "stopped":
        return RecordingStatus.Stopped;
      default:
        return RecordingStatus.Unset;
    }
  }
}

/// Recording Object - represent a recording file
class Recording {
  /// File path
  String? path;

  /// Extension
  String? extension;

  /// Duration in milliseconds
  Duration? duration;

  /// Audio format
  AudioFormat? audioFormat;

  /// Metering
  AudioMetering? metering;

  /// Is currently recording
  RecordingStatus? status;
}

/// Audio Metering Level - describe the metering level of microphone when recording
class AudioMetering {
  /// Represent peak level of given short duration
  double? peakPower;

  /// Represent average level of given short duration
  double? averagePower;

  /// Is metering enabled in system
  bool? isMeteringEnabled;

  AudioMetering({this.peakPower, this.averagePower, this.isMeteringEnabled});
}

/// Represent the status of a Recording
enum RecordingStatus {
  /// Recording not initialized
  Unset,

  /// Ready for start recording
  Initialized,

  /// Currently recording
  Recording,

  /// Currently Paused
  Paused,

  /// This specific recording Stopped, cannot be start again
  Stopped,
}

/// Audio Format,
/// WAV is lossless audio, recommended
enum AudioFormat {
  AAC,
  WAV,
}
