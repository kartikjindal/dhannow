
import 'package:file_picker/file_picker.dart' as fp;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import 'api/api.dart';
import 'widgets/widgets.dart';

/// A file the borrower photographed or chose, ready to upload. The demo server's files have no
/// [data] (a tap adds a named stand-in).
class PickedFile {
  const PickedFile(this.name, {this.bytes = 420 * 1024, this.data});
  final String name;
  final int bytes;
  final Uint8List? data;

  bool get pdf => name.toLowerCase().endsWith('.pdf');
  String get contentType => pdf ? 'application/pdf' : name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
}

/// One rule for every upload, the same as the server takes: PDF, JPG or PNG, 5 MB at most.
const maxUploadBytes = 5 * 1024 * 1024;
const uploadExtensions = ['pdf', 'jpg', 'jpeg', 'png'];
const uploadHint = 'PDF or photo (JPG, PNG) up to 5 MB';

/// The borrower-facing problem with [f], or null when it can go up.
String? uploadProblem(PickedFile f) {
  final ext = f.name.contains('.') ? f.name.split('.').last.toLowerCase() : '';
  if (!uploadExtensions.contains(ext)) return 'Choose a PDF, JPG or PNG file.';
  if (f.bytes > maxUploadBytes) return f.pdf ? 'This PDF is over 5 MB. Choose a smaller file or take a photo instead.' : 'This photo is over 5 MB. Choose a smaller one.';
  return null;
}

/// How a file is added: the camera (back, or [selfie] front), or the file chooser ([pdf] false
/// limits it to images, as for the payment screenshot).
enum PickFrom { camera, selfie, files, images }

/// Opens the camera or the file chooser. Null when the borrower backs out, the camera is blocked
/// (E9 opens), or the file breaks the rule (a toast says which). Images are scaled down on the
/// phone (CC-49) so a photo stays well under the limit.
Future<PickedFile?> pickFile(BuildContext context, PickFrom from, {String? name}) async {
  if (demoControls) {
    // The demo has no plugins in tests: a named stand-in, as the boards show.
    final ext = from == PickFrom.files ? 'pdf' : 'jpg';
    return PickedFile('${name ?? 'document'}.$ext');
  }
  try {
    final PickedFile f;
    if (from == PickFrom.camera || from == PickFrom.selfie) {
      final x = await ImagePicker().pickImage(
        source: ImageSource.camera,
        preferredCameraDevice: from == PickFrom.selfie ? CameraDevice.front : CameraDevice.rear,
        imageQuality: 75,
        maxWidth: 1800,
        maxHeight: 1800,
      );
      if (x == null) return null;
      final data = await x.readAsBytes();
      f = PickedFile('${name ?? 'photo'}.jpg', bytes: data.length, data: data);
    } else {
      final p = await fp.FilePicker.pickFile(
        type: fp.FileType.custom,
        allowedExtensions: from == PickFrom.images ? const ['jpg', 'jpeg', 'png'] : uploadExtensions,
      );
      if (p == null) return null;
      final size = await p.length() ?? 0;
      // Refuse before reading a large file into memory.
      if (size > maxUploadBytes) {
        if (context.mounted) showDnToast(context, uploadProblem(PickedFile(p.name, bytes: size))!, error: true);
        return null;
      }
      final data = await p.readAsBytes();
      f = PickedFile(p.name, bytes: data.length, data: data);
    }
    final problem = uploadProblem(f);
    if (problem != null) {
      if (context.mounted) showDnToast(context, problem, error: true);
      return null;
    }
    return f;
  } on PlatformException catch (e) {
    if (!context.mounted) return null;
    if (e.code.contains('denied') || e.code.contains('access')) {
      context.push('/camera-denied', extra: from == PickFrom.selfie ? 'selfie' : null);
    } else {
      showDnToast(context, 'Couldn’t open that. Try again.', error: true);
    }
    return null;
  }
}

/// E9 "Open settings": the app's page in Android settings (a one-method channel, no plugin).
Future<void> openAppSettings() => const MethodChannel('dhannow/system').invokeMethod<void>('openAppSettings');
