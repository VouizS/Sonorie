import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

const String sonorieVersion = '0.4.2-r1';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final controller = SonorieController();
  await controller.initialize();
  runApp(SonorieApp(controller: controller));
}

class AudioTrack {
  const AudioTrack({
    required this.path,
    required this.title,
    required this.artist,
    required this.folder,
    required this.extension,
    required this.sizeBytes,
  });

  final String path;
  final String title;
  final String artist;
  final String folder;
  final String extension;
  final int sizeBytes;

  factory AudioTrack.fromMap(Map<String, dynamic> map) {
    return AudioTrack(
      path: map['path'] as String? ?? '',
      title: map['title'] as String? ?? 'Sem título',
      artist: map['artist'] as String? ?? 'Artista desconhecido',
      folder: map['folder'] as String? ?? 'Armazenamento',
      extension: map['extension'] as String? ?? '',
      sizeBytes: map['sizeBytes'] as int? ?? 0,
    );
  }
}

List<Map<String, dynamic>> scanAudioFiles(List<String> roots) {
  const allowed = <String>{
    'mp3',
    'm4a',
    'aac',
    'wav',
    'ogg',
    'flac',
    'opus',
    '3gp',
  };

  final found = <String, Map<String, dynamic>>{};

  for (final root in roots) {
    try {
      final directory = Directory(root);
      if (!directory.existsSync()) continue;

      for (final entity in directory.listSync(recursive: true, followLinks: false)) {
        if (entity is! File) continue;
        final path = entity.path;
        final dot = path.lastIndexOf('.');
        if (dot < 0) continue;
        final extension = path.substring(dot + 1).toLowerCase();
        if (!allowed.contains(extension)) continue;

        final slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        final fileName = slash >= 0 ? path.substring(slash + 1) : path;
        final rawName = fileName.substring(0, fileName.length - extension.length - 1);
        final cleaned = _cleanAudioName(rawName);
        final parentPath = slash > 0 ? path.substring(0, slash) : '';
        final parentSlash = math.max(parentPath.lastIndexOf('/'), parentPath.lastIndexOf('\\'));
        final folder = parentSlash >= 0 ? parentPath.substring(parentSlash + 1) : parentPath;
        final parsed = _parseArtistAndTitle(cleaned, folder);

        int size = 0;
        try {
          size = entity.lengthSync();
        } catch (_) {}

        found[path] = <String, dynamic>{
          'path': path,
          'title': parsed.$2,
          'artist': parsed.$1,
          'folder': folder.isEmpty ? 'Armazenamento' : folder,
          'extension': extension.toUpperCase(),
          'sizeBytes': size,
        };
      }
    } catch (_) {
      // Uma pasta bloqueada não interrompe a varredura das demais.
    }
  }

  final result = found.values.toList();
  result.sort((a, b) {
    final artistCompare = (a['artist'] as String).toLowerCase().compareTo(
          (b['artist'] as String).toLowerCase(),
        );
    if (artistCompare != 0) return artistCompare;
    return (a['title'] as String).toLowerCase().compareTo(
          (b['title'] as String).toLowerCase(),
        );
  });
  return result;
}

String _cleanAudioName(String input) {
  return input
      .replaceAll(RegExp(r'\(MP3[_ -]?\d+K\)', caseSensitive: false), '')
      .replaceAll(RegExp(r'\(Official Music Video\)', caseSensitive: false), '')
      .replaceAll(RegExp(r'\(Official Audio\)', caseSensitive: false), '')
      .replaceAll(RegExp(r'\[Official.*?\]', caseSensitive: false), '')
      .replaceAll(RegExp(r'_+'), ' ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
}

(String, String) _parseArtistAndTitle(String raw, String folder) {
  final separators = <String>[' - ', ' – ', ' — '];
  for (final separator in separators) {
    final index = raw.indexOf(separator);
    if (index > 0 && index < raw.length - separator.length) {
      final artist = raw.substring(0, index).trim();
      final title = raw.substring(index + separator.length).trim();
      if (artist.isNotEmpty && title.isNotEmpty) return (artist, title);
    }
  }

  final fallbackArtist = folder.toLowerCase().contains('snaptube')
      ? 'Artista não identificado'
      : folder.isEmpty
          ? 'Artista desconhecido'
          : folder;
  return (fallbackArtist, raw.isEmpty ? 'Sem título' : raw);
}

class SonorieController extends ChangeNotifier {
  final AudioPlayer player = AudioPlayer();

  SharedPreferences? _prefs;
  bool initialized = false;
  bool onboarded = false;
  bool scanning = false;
  bool permissionGranted = false;
  String? libraryMessage;
  String? playerMessage;
  ThemeMode themeMode = ThemeMode.system;

  final List<AudioTrack> songs = <AudioTrack>[];
  final Set<String> favoritePaths = <String>{};
  final Set<String> favoriteArtists = <String>{};
  final Set<String> favoriteGenres = <String>{};

  List<AudioTrack> queue = <AudioTrack>[];
  int currentIndex = -1;
  Duration position = Duration.zero;
  Duration duration = Duration.zero;
  bool isPlaying = false;
  bool _handlingCompletion = false;
  int _lastNotifiedSecond = -1;

  AudioTrack? get currentTrack {
    if (currentIndex < 0 || currentIndex >= queue.length) return null;
    return queue[currentIndex];
  }

  Future<void> initialize() async {
    _prefs = await SharedPreferences.getInstance();
    onboarded = _prefs?.getBool('onboarded') ?? false;
    favoriteArtists.addAll(_prefs?.getStringList('favoriteArtists') ?? const <String>[]);
    favoriteGenres.addAll(_prefs?.getStringList('favoriteGenres') ?? const <String>[]);
    favoritePaths.addAll(_prefs?.getStringList('favoritePaths') ?? const <String>[]);
    themeMode = _decodeTheme(_prefs?.getString('themeMode'));

    player.positionStream.listen((value) {
      position = value;
      final second = value.inSeconds;
      if (second != _lastNotifiedSecond) {
        _lastNotifiedSecond = second;
        notifyListeners();
      }
    });
    player.durationStream.listen((value) {
      duration = value ?? Duration.zero;
      notifyListeners();
    });
    player.playerStateStream.listen((state) {
      isPlaying = state.playing;
      notifyListeners();
      if (state.processingState == ProcessingState.completed && !_handlingCompletion) {
        _handlingCompletion = true;
        Future<void>.microtask(() async {
          await playNext();
          _handlingCompletion = false;
        });
      }
    });

    permissionGranted = await _hasMediaPermission();
    initialized = true;
    notifyListeners();
    if (permissionGranted) {
      unawaited(scanLibrary(requestPermission: false));
    }
  }

  ThemeMode _decodeTheme(String? raw) {
    switch (raw) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }

  Future<void> setTheme(ThemeMode mode) async {
    themeMode = mode;
    final raw = mode == ThemeMode.light
        ? 'light'
        : mode == ThemeMode.dark
            ? 'dark'
            : 'system';
    await _prefs?.setString('themeMode', raw);
    notifyListeners();
  }

  Future<void> saveTaste(Set<String> artists, Set<String> genres) async {
    favoriteArtists
      ..clear()
      ..addAll(artists);
    favoriteGenres
      ..clear()
      ..addAll(genres);
    onboarded = true;
    await _prefs?.setBool('onboarded', true);
    await _prefs?.setStringList('favoriteArtists', favoriteArtists.toList());
    await _prefs?.setStringList('favoriteGenres', favoriteGenres.toList());
    notifyListeners();
  }

  Future<bool> _hasMediaPermission() async {
    try {
      final audio = await Permission.audio.status;
      final storage = await Permission.storage.status;
      return audio.isGranted || storage.isGranted;
    } catch (_) {
      return false;
    }
  }

  Future<bool> requestMediaPermission() async {
    try {
      final audio = await Permission.audio.request();
      final storage = await Permission.storage.request();
      permissionGranted = audio.isGranted || storage.isGranted;
    } catch (_) {
      permissionGranted = false;
    }
    notifyListeners();
    return permissionGranted;
  }

  Future<void> scanLibrary({bool requestPermission = true}) async {
    if (scanning) return;
    libraryMessage = null;

    if (requestPermission && !permissionGranted) {
      final granted = await requestMediaPermission();
      if (!granted) {
        libraryMessage = 'Permissão de áudio não concedida.';
        notifyListeners();
        return;
      }
    }

    if (!permissionGranted) {
      permissionGranted = await _hasMediaPermission();
      if (!permissionGranted) {
        libraryMessage = 'Toque em Permitir músicas para liberar a biblioteca.';
        notifyListeners();
        return;
      }
    }

    scanning = true;
    notifyListeners();

    try {
      final roots = await _discoverRoots();
      final raw = await compute<List<String>, List<Map<String, dynamic>>>(
        scanAudioFiles,
        roots.toList(),
      );
      songs
        ..clear()
        ..addAll(raw.map(AudioTrack.fromMap));
      libraryMessage = songs.isEmpty
          ? 'Nenhuma música encontrada nas pastas liberadas.'
          : '${songs.length} músicas reais encontradas.';
    } catch (error) {
      libraryMessage = 'Falha ao varrer músicas: $error';
    } finally {
      scanning = false;
      notifyListeners();
    }
  }

  Future<Set<String>> _discoverRoots() async {
    final roots = <String>{
      '/storage/emulated/0/Music',
      '/storage/emulated/0/Download',
      '/storage/emulated/0/Downloads',
      '/storage/emulated/0/Recordings',
      '/storage/emulated/0/WhatsApp/Media/WhatsApp Audio',
      '/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio',
      '/storage/emulated/0/SnapTube Audio',
      '/storage/emulated/0/Snaptube',
      '/storage/3130-6234/SnapTube Audio',
      '/storage/3130-6234/Snaptube',
      '/storage/3130-6234/Music',
      '/storage/3130-6234/Download',
      '/storage/3130-6234/Downloads',
      '/storage/3130-6234/Audio',
    };

    try {
      final storage = Directory('/storage');
      if (await storage.exists()) {
        await for (final entity in storage.list(followLinks: false)) {
          if (entity is! Directory) continue;
          final base = entity.path;
          final name = base.split('/').last.toLowerCase();
          if (name == 'emulated' || name == 'self') continue;
          roots.addAll(<String>{
            '$base/SnapTube Audio',
            '$base/Snaptube',
            '$base/Music',
            '$base/Download',
            '$base/Downloads',
            '$base/Audio',
          });
        }
      }
    } catch (_) {}

    return roots;
  }

  Future<void> playTrack(AudioTrack track, {List<AudioTrack>? fromQueue}) async {
    final selectedQueue = fromQueue == null || fromQueue.isEmpty ? songs : fromQueue;
    final index = selectedQueue.indexWhere((item) => item.path == track.path);
    queue = List<AudioTrack>.from(selectedQueue);
    currentIndex = index >= 0 ? index : 0;
    position = Duration.zero;
    duration = Duration.zero;
    playerMessage = null;
    notifyListeners();

    try {
      await player.setFilePath(track.path);
      await player.play();
    } catch (error) {
      playerMessage = 'Não foi possível tocar este arquivo: $error';
      isPlaying = false;
      notifyListeners();
    }
  }

  Future<void> togglePlayPause() async {
    if (currentTrack == null) return;
    if (player.playing) {
      await player.pause();
    } else {
      if (player.processingState == ProcessingState.completed) {
        await player.seek(Duration.zero);
      }
      await player.play();
    }
  }

  Future<void> playNext() async {
    if (queue.isEmpty) return;
    final next = currentIndex + 1;
    if (next >= queue.length) {
      await player.pause();
      await player.seek(Duration.zero);
      return;
    }
    await playTrack(queue[next], fromQueue: queue);
  }

  Future<void> playPrevious() async {
    if (queue.isEmpty) return;
    if (position > const Duration(seconds: 4)) {
      await player.seek(Duration.zero);
      return;
    }
    final previous = currentIndex - 1;
    if (previous < 0) {
      await player.seek(Duration.zero);
      return;
    }
    await playTrack(queue[previous], fromQueue: queue);
  }

  Future<void> seek(Duration value) => player.seek(value);

  bool isFavorite(AudioTrack track) => favoritePaths.contains(track.path);

  Future<void> toggleFavorite(AudioTrack track) async {
    if (!favoritePaths.add(track.path)) {
      favoritePaths.remove(track.path);
    }
    await _prefs?.setStringList('favoritePaths', favoritePaths.toList());
    notifyListeners();
  }

  @override
  void dispose() {
    player.dispose();
    super.dispose();
  }
}

class SonorieApp extends StatelessWidget {
  const SonorieApp({super.key, required this.controller});

  final SonorieController controller;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          title: 'Sonorie',
          themeMode: controller.themeMode,
          theme: _theme(Brightness.light),
          darkTheme: _theme(Brightness.dark),
          home: SonorieRoot(controller: controller),
        );
      },
    );
  }

  ThemeData _theme(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFFB79CFF),
      brightness: brightness,
      surface: dark ? const Color(0xFF0F0C14) : const Color(0xFFF7F2FA),
    );
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      textTheme: const TextTheme(
        headlineMedium: TextStyle(fontWeight: FontWeight.w800),
        headlineSmall: TextStyle(fontWeight: FontWeight.w800),
        titleLarge: TextStyle(fontWeight: FontWeight.w800),
        titleMedium: TextStyle(fontWeight: FontWeight.w700),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest.withOpacity(0.24),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(28),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(28),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
      ),
    );
  }
}

class SonorieRoot extends StatefulWidget {
  const SonorieRoot({super.key, required this.controller});

  final SonorieController controller;

  @override
  State<SonorieRoot> createState() => _SonorieRootState();
}

class _SonorieRootState extends State<SonorieRoot> {
  int selectedIndex = 0;
  bool dockExpanded = true;
  bool editingTaste = false;

  @override
  Widget build(BuildContext context) {
    if (!widget.controller.initialized) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    if (!widget.controller.onboarded || editingTaste) {
      return TasteOnboardingScreen(
        controller: widget.controller,
        editing: widget.controller.onboarded,
        onDone: () => setState(() => editingTaste = false),
      );
    }

    final screens = <Widget>[
      HomeScreen(controller: widget.controller, onEditTaste: _editTaste),
      LibraryScreen(controller: widget.controller),
      PlayerScreen(controller: widget.controller),
      SettingsScreen(controller: widget.controller, onEditTaste: _editTaste),
    ];

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: selectedIndex, children: screens),
      ),
      bottomNavigationBar: SonorieBottomDock(
        controller: widget.controller,
        selectedIndex: selectedIndex,
        expanded: dockExpanded,
        onToggle: () => setState(() => dockExpanded = !dockExpanded),
        onSelected: (value) => setState(() => selectedIndex = value),
      ),
    );
  }

  void _editTaste() => setState(() => editingTaste = true);
}

class TasteOnboardingScreen extends StatefulWidget {
  const TasteOnboardingScreen({
    super.key,
    required this.controller,
    required this.editing,
    required this.onDone,
  });

  final SonorieController controller;
  final bool editing;
  final VoidCallback onDone;

  @override
  State<TasteOnboardingScreen> createState() => _TasteOnboardingScreenState();
}

class _TasteOnboardingScreenState extends State<TasteOnboardingScreen> {
  static const artists = <String>[
    'Yeat',
    'Ken Carson',
    'Playboi Carti',
    'Nine Vicious',
    'Destroy Lonely',
    'Homixide Gang',
    'Travis Scott',
    'Future',
    'Lil Uzi Vert',
    '21 Savage',
    'Drake',
    'The Weeknd',
    'Kendrick Lamar',
    'Tyler, The Creator',
    'A\$AP Rocky',
    'Metro Boomin',
    'SZA',
    'Doja Cat',
    'Billie Eilish',
    'Ariana Grande',
    'Dua Lipa',
    'Bruno Mars',
    'Post Malone',
    'Bad Bunny',
    'Anitta',
    'Matuê',
    'Teto',
    'Wiu',
    'Veigh',
    'MC Cabelinho',
    'Luan Santana',
    'Gusttavo Lima',
  ];

  static const genres = <String>[
    'Rage',
    'Trap',
    'Hip-hop',
    'Pluggnb',
    'Rap',
    'R&B',
    'Pop',
    'Funk',
    'Phonk',
    'Eletrônica',
    'Lo-fi',
    'Rock',
    'Indie',
    'Metal',
    'Sertanejo',
    'Gospel',
    'Jazz',
    'MPB',
    'Reggaeton',
    'Afrobeat',
    'K-pop',
    'Hyperpop',
    'Drill',
    'House',
  ];

  late final Set<String> selectedArtists;
  late final Set<String> selectedGenres;

  @override
  void initState() {
    super.initState();
    selectedArtists = Set<String>.from(widget.controller.favoriteArtists);
    selectedGenres = Set<String>.from(widget.controller.favoriteGenres);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
          children: <Widget>[
            Text('Sonorie', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 18),
            Text(
              widget.editing ? 'Ajuste seu gosto musical' : 'Monte seu gosto musical',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 10),
            Text(
              'Escolha artistas e gêneros para organizar sua experiência. Os nomes agora têm mais altura, respiro e leitura.',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 24),
            _InfoCard(
              icon: Icons.auto_awesome_rounded,
              title: 'Descoberta personalizada',
              text: 'A seleção é salva localmente. Imagens só entram quando forem reais e seguras.',
            ),
            const SizedBox(height: 28),
            Text('Artistas favoritos', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 14),
            _ChoiceGrid(
              items: artists,
              selected: selectedArtists,
              onChanged: (value) => setState(() {
                if (!selectedArtists.add(value)) selectedArtists.remove(value);
              }),
            ),
            const SizedBox(height: 30),
            Text('Gêneros musicais', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 14),
            _ChoiceGrid(
              items: genres,
              selected: selectedGenres,
              onChanged: (value) => setState(() {
                if (!selectedGenres.add(value)) selectedGenres.remove(value);
              }),
            ),
            const SizedBox(height: 30),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(58),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
              ),
              onPressed: () async {
                await widget.controller.saveTaste(selectedArtists, selectedGenres);
                widget.onDone();
              },
              icon: const Icon(Icons.favorite_rounded),
              label: Text(widget.editing ? 'Salvar escolhas' : 'Entrar no Sonorie'),
            ),
            if (!widget.editing)
              TextButton(
                onPressed: () async {
                  await widget.controller.saveTaste(<String>{}, <String>{});
                  widget.onDone();
                },
                child: const Text('Pular por enquanto'),
              ),
          ],
        ),
      ),
    );
  }
}

class _ChoiceGrid extends StatelessWidget {
  const _ChoiceGrid({
    required this.items,
    required this.selected,
    required this.onChanged,
  });

  final List<String> items;
  final Set<String> selected;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const gap = 12.0;
        final width = (constraints.maxWidth - gap) / 2;
        return Wrap(
          spacing: gap,
          runSpacing: 12,
          children: items.map((item) {
            final active = selected.contains(item);
            return SizedBox(
              width: width,
              child: Material(
                color: active
                    ? Theme.of(context).colorScheme.primaryContainer
                    : Theme.of(context).colorScheme.surfaceContainerLow,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(24),
                  side: BorderSide(
                    color: active
                        ? Theme.of(context).colorScheme.primary
                        : Theme.of(context).colorScheme.outlineVariant,
                  ),
                ),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: () => onChanged(item),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minHeight: 58),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                      child: Row(
                        children: <Widget>[
                          Icon(
                            active ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                            size: 23,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              item,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                                    fontWeight: FontWeight.w700,
                                    height: 1.15,
                                  ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            );
          }).toList(),
        );
      },
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.controller, required this.onEditTaste});

  final SonorieController controller;
  final VoidCallback onEditTaste;

  @override
  Widget build(BuildContext context) {
    final primaryArtist = controller.favoriteArtists.isEmpty
        ? 'seu gosto'
        : controller.favoriteArtists.first;
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return ListView(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 28),
          children: <Widget>[
            Center(
              child: Column(
                children: <Widget>[
                  Text('Sonorie', style: Theme.of(context).textTheme.headlineMedium),
                  const SizedBox(height: 2),
                  const Text('Música offline com alma'),
                ],
              ),
            ),
            const SizedBox(height: 28),
            Container(
              padding: const EdgeInsets.all(26),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: <Color>[Color(0xFF544097), Color(0xFF7D586B)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(34),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  const CircleAvatar(radius: 30, child: Icon(Icons.music_note_rounded, size: 32)),
                  const SizedBox(height: 24),
                  Text(
                    'Sua biblioteca local, com visual moderno.',
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(color: Colors.white),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    controller.permissionGranted
                        ? '${controller.songs.length} músicas reais disponíveis.'
                        : 'Permita acesso às músicas para o Sonorie carregar arquivos reais.',
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: Colors.white70),
                  ),
                  const SizedBox(height: 18),
                  FilledButton.icon(
                    onPressed: () => controller.scanLibrary(),
                    icon: Icon(controller.scanning ? Icons.sync_rounded : Icons.play_arrow_rounded),
                    label: Text(controller.scanning ? 'Varrendo...' : 'Permitir músicas'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            _SectionCard(
              icon: Icons.favorite_rounded,
              title: 'Seu gosto musical',
              text: <String>[
                ...controller.favoriteArtists.take(3),
                ...controller.favoriteGenres.take(2),
              ].join(', ').trim().isEmpty
                  ? 'Ainda não definido'
                  : <String>[
                      ...controller.favoriteArtists.take(3),
                      ...controller.favoriteGenres.take(2),
                    ].join(', '),
              actionLabel: 'Editar gosto musical',
              onAction: onEditTaste,
            ),
            const SizedBox(height: 24),
            Text('Para você', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            _InfoCard(
              icon: Icons.auto_awesome_rounded,
              title: 'Descobertas para $primaryArtist',
              text: 'O Sonorie usa artistas, gêneros e músicas locais para organizar sua experiência. Descoberta online entra depois com APIs oficiais.',
            ),
            const SizedBox(height: 24),
            Text('Resumo local', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(child: _StatCard(label: 'Músicas', value: '${controller.songs.length}')),
                const SizedBox(width: 12),
                const Expanded(child: _StatCard(label: 'Modo', value: 'Flutter')),
              ],
            ),
            const SizedBox(height: 24),
            Text('Últimas músicas encontradas', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            if (controller.songs.isEmpty)
              _InfoCard(
                icon: Icons.folder_open_rounded,
                title: 'Nenhuma música carregada ainda',
                text: controller.libraryMessage ?? 'Permita músicas para listar arquivos reais. Nada será simulado.',
              )
            else
              ...controller.songs.take(4).map(
                    (track) => Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: TrackTile(
                        track: track,
                        controller: controller,
                        queue: controller.songs,
                      ),
                    ),
                  ),
          ],
        );
      },
    );
  }
}

class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.controller});

  final SonorieController controller;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  final TextEditingController searchController = TextEditingController();
  String query = '';

  @override
  void dispose() {
    searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final filtered = widget.controller.songs.where((track) {
          final q = query.toLowerCase().trim();
          if (q.isEmpty) return true;
          return track.title.toLowerCase().contains(q) ||
              track.artist.toLowerCase().contains(q) ||
              track.folder.toLowerCase().contains(q) ||
              track.extension.toLowerCase().contains(q);
        }).toList();

        return Column(
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 28, 24, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text('Sonorie', style: Theme.of(context).textTheme.headlineMedium),
                  const Text('Biblioteca local real'),
                  const SizedBox(height: 22),
                  Text('Biblioteca local', style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 6),
                  Text('${widget.controller.songs.length} músicas reais encontradas no aparelho'),
                  const SizedBox(height: 16),
                  Row(
                    children: <Widget>[
                      FilledButton.icon(
                        onPressed: widget.controller.scanning
                            ? null
                            : () => widget.controller.scanLibrary(),
                        icon: const Icon(Icons.refresh_rounded),
                        label: Text(widget.controller.scanning ? 'Varrendo...' : 'Atualizar'),
                      ),
                      const SizedBox(width: 12),
                      const Text('Leitura real', style: TextStyle(fontWeight: FontWeight.w700)),
                    ],
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: searchController,
                    onChanged: (value) => setState(() => query = value),
                    decoration: const InputDecoration(
                      prefixIcon: Icon(Icons.search_rounded),
                      hintText: 'Buscar música, artista, pasta ou formato',
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: widget.controller.scanning
                  ? const Center(child: CircularProgressIndicator())
                  : filtered.isEmpty
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(28),
                            child: _InfoCard(
                              icon: Icons.folder_open_rounded,
                              title: 'Nenhuma música nesta busca',
                              text: widget.controller.libraryMessage ?? 'Atualize a biblioteca ou mude o termo pesquisado.',
                            ),
                          ),
                        )
                      : ListView.builder(
                          padding: const EdgeInsets.fromLTRB(24, 4, 24, 30),
                          itemCount: filtered.length,
                          itemBuilder: (context, index) {
                            return Padding(
                              padding: const EdgeInsets.only(bottom: 10),
                              child: TrackTile(
                                track: filtered[index],
                                controller: widget.controller,
                                queue: filtered,
                              ),
                            );
                          },
                        ),
            ),
          ],
        );
      },
    );
  }
}

class TrackTile extends StatelessWidget {
  const TrackTile({
    super.key,
    required this.track,
    required this.controller,
    required this.queue,
  });

  final AudioTrack track;
  final SonorieController controller;
  final List<AudioTrack> queue;

  @override
  Widget build(BuildContext context) {
    final active = controller.currentTrack?.path == track.path;
    return Material(
      color: active
          ? Theme.of(context).colorScheme.primaryContainer
          : Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(28),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => controller.playTrack(track, fromQueue: queue),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
          child: Row(
            children: <Widget>[
              CircleAvatar(
                radius: 28,
                child: Icon(active && controller.isPlaying ? Icons.graphic_eq_rounded : Icons.music_note_rounded),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(track.title, maxLines: 2, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 3),
                    Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
                    const SizedBox(height: 3),
                    Text('${track.extension} • ${_formatBytes(track.sizeBytes)} • ${track.folder}', maxLines: 1, overflow: TextOverflow.ellipsis),
                  ],
                ),
              ),
              IconButton(
                tooltip: controller.isFavorite(track) ? 'Remover dos favoritos' : 'Favoritar',
                onPressed: () => controller.toggleFavorite(track),
                icon: Icon(controller.isFavorite(track) ? Icons.favorite_rounded : Icons.favorite_border_rounded),
              ),
              IconButton(
                tooltip: 'Tocar',
                onPressed: () => controller.playTrack(track, fromQueue: queue),
                icon: const Icon(Icons.play_arrow_rounded),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class PlayerScreen extends StatelessWidget {
  const PlayerScreen({super.key, required this.controller});

  final SonorieController controller;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final track = controller.currentTrack;
        return ListView(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 34),
          children: <Widget>[
            Center(
              child: Column(
                children: <Widget>[
                  Text('Sonorie', style: Theme.of(context).textTheme.headlineMedium),
                  Text(track == null ? 'Player real' : 'Tocando agora'),
                ],
              ),
            ),
            const SizedBox(height: 30),
            if (track == null)
              _InfoCard(
                icon: Icons.play_circle_outline_rounded,
                title: 'Escolha uma música real',
                text: 'Abra a Biblioteca e toque em qualquer arquivo. Os controles só aparecem depois que o áudio real for carregado.',
              )
            else ...<Widget>[
              Container(
                height: 280,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(36),
                ),
                child: const Center(child: Icon(Icons.music_note_rounded, size: 110)),
              ),
              const SizedBox(height: 24),
              Text(track.title, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 6),
              Text(track.artist, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(track.folder, textAlign: TextAlign.center),
              const SizedBox(height: 22),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: <Widget>[
                  FilledButton.tonalIcon(
                    onPressed: () => controller.toggleFavorite(track),
                    icon: Icon(controller.isFavorite(track) ? Icons.favorite_rounded : Icons.favorite_border_rounded),
                    label: Text(controller.isFavorite(track) ? 'Favorita' : 'Favoritar'),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              _ProgressControl(controller: controller),
              const SizedBox(height: 18),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: <Widget>[
                  IconButton.filledTonal(
                    iconSize: 36,
                    onPressed: controller.playPrevious,
                    icon: const Icon(Icons.skip_previous_rounded),
                  ),
                  const SizedBox(width: 24),
                  IconButton.filled(
                    iconSize: 50,
                    padding: const EdgeInsets.all(20),
                    onPressed: controller.togglePlayPause,
                    icon: Icon(controller.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded),
                  ),
                  const SizedBox(width: 24),
                  IconButton.filledTonal(
                    iconSize: 36,
                    onPressed: controller.playNext,
                    icon: const Icon(Icons.skip_next_rounded),
                  ),
                ],
              ),
              if (controller.playerMessage != null) ...<Widget>[
                const SizedBox(height: 18),
                Text(controller.playerMessage!, textAlign: TextAlign.center),
              ],
            ],
          ],
        );
      },
    );
  }
}

class _ProgressControl extends StatelessWidget {
  const _ProgressControl({required this.controller});

  final SonorieController controller;

  @override
  Widget build(BuildContext context) {
    final maximum = math.max(1, controller.duration.inMilliseconds).toDouble();
    final value = math.min(controller.position.inMilliseconds.toDouble(), maximum);
    return Column(
      children: <Widget>[
        Slider(
          value: value,
          max: maximum,
          onChanged: (raw) => controller.seek(Duration(milliseconds: raw.round())),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: <Widget>[
              Text(_formatDuration(controller.position)),
              Text(_formatDuration(controller.duration)),
            ],
          ),
        ),
      ],
    );
  }
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.controller, required this.onEditTaste});

  final SonorieController controller;
  final VoidCallback onEditTaste;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return ListView(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 34),
          children: <Widget>[
            Center(
              child: Column(
                children: <Widget>[
                  Text('Sonorie', style: Theme.of(context).textTheme.headlineMedium),
                  const Text('Ajustes'),
                ],
              ),
            ),
            const SizedBox(height: 28),
            Text('Aparência', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            _ThemeButton(label: 'Usar tema do sistema', selected: controller.themeMode == ThemeMode.system, onPressed: () => controller.setTheme(ThemeMode.system)),
            const SizedBox(height: 10),
            _ThemeButton(label: 'Modo claro', selected: controller.themeMode == ThemeMode.light, onPressed: () => controller.setTheme(ThemeMode.light)),
            const SizedBox(height: 10),
            _ThemeButton(label: 'Modo escuro', selected: controller.themeMode == ThemeMode.dark, onPressed: () => controller.setTheme(ThemeMode.dark)),
            const SizedBox(height: 28),
            Text('Status do app', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                borderRadius: BorderRadius.circular(30),
              ),
              child: Column(
                children: <Widget>[
                  _StatusRow(label: 'Base', value: 'Flutter real'),
                  _StatusRow(label: 'Versão', value: sonorieVersion),
                  _StatusRow(label: 'Permissão de mídia', value: controller.permissionGranted ? 'Permitida' : 'Pendente'),
                  _StatusRow(label: 'Biblioteca', value: controller.scanning ? 'Varrendo' : '${controller.songs.length} músicas'),
                  _StatusRow(label: 'Player', value: controller.currentTrack == null ? 'Pronto' : 'Conectado'),
                  const _StatusRow(label: 'Dock inferior', value: 'Toggle por clique'),
                  const _StatusRow(label: 'Imagens de artista', value: 'Somente real/segura'),
                ],
              ),
            ),
            const SizedBox(height: 20),
            FilledButton.tonalIcon(
              onPressed: onEditTaste,
              icon: const Icon(Icons.favorite_rounded),
              label: const Text('Editar gosto musical'),
            ),
            const SizedBox(height: 12),
            FilledButton.tonalIcon(
              onPressed: controller.scanning ? null : () => controller.scanLibrary(),
              icon: const Icon(Icons.refresh_rounded),
              label: Text(controller.scanning ? 'Varrendo músicas...' : 'Atualizar biblioteca'),
            ),
            const SizedBox(height: 22),
            _InfoCard(
              icon: Icons.route_rounded,
              title: 'Próxima evolução',
              text: 'v0.4.2-r2: reprodução em segundo plano, notificação de mídia e seletor de pasta SAF para cartões bloqueados pelo Android.',
            ),
          ],
        );
      },
    );
  }
}

class SonorieBottomDock extends StatelessWidget {
  const SonorieBottomDock({
    super.key,
    required this.controller,
    required this.selectedIndex,
    required this.expanded,
    required this.onToggle,
    required this.onSelected,
  });

  final SonorieController controller;
  final int selectedIndex;
  final bool expanded;
  final VoidCallback onToggle;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          return Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: onToggle,
                child: SizedBox(
                  height: 30,
                  width: double.infinity,
                  child: Center(
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 220),
                      width: expanded ? 62 : 82,
                      height: 6,
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.outline,
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                ),
              ),
              AnimatedCrossFade(
                duration: const Duration(milliseconds: 240),
                crossFadeState: expanded ? CrossFadeState.showFirst : CrossFadeState.showSecond,
                firstChild: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    if (controller.currentTrack != null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
                        child: _MiniPlayer(controller: controller),
                      ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
                      child: NavigationBar(
                        selectedIndex: selectedIndex,
                        onDestinationSelected: onSelected,
                        destinations: const <NavigationDestination>[
                          NavigationDestination(icon: Icon(Icons.home_rounded), label: 'Início'),
                          NavigationDestination(icon: Icon(Icons.music_note_rounded), label: 'Biblioteca'),
                          NavigationDestination(icon: Icon(Icons.play_circle_rounded), label: 'Player'),
                          NavigationDestination(icon: Icon(Icons.settings_rounded), label: 'Ajustes'),
                        ],
                      ),
                    ),
                  ],
                ),
                secondChild: const SizedBox(height: 2, width: double.infinity),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _MiniPlayer extends StatelessWidget {
  const _MiniPlayer({required this.controller});

  final SonorieController controller;

  @override
  Widget build(BuildContext context) {
    final track = controller.currentTrack!;
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(28),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: <Widget>[
            const CircleAvatar(child: Icon(Icons.graphic_eq_rounded)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                  Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
            IconButton(onPressed: controller.playPrevious, icon: const Icon(Icons.skip_previous_rounded)),
            IconButton(onPressed: controller.togglePlayPause, icon: Icon(controller.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded)),
            IconButton(onPressed: controller.playNext, icon: const Icon(Icons.skip_next_rounded)),
          ],
        ),
      ),
    );
  }
}

class _ThemeButton extends StatelessWidget {
  const _ThemeButton({required this.label, required this.selected, required this.onPressed});

  final String label;
  final bool selected;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return FilledButton.tonal(
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(56),
        side: selected ? BorderSide(color: Theme.of(context).colorScheme.primary) : null,
      ),
      onPressed: onPressed,
      child: Text(label),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: <Widget>[
          Expanded(child: Text(label)),
          const SizedBox(width: 16),
          Flexible(child: Text(value, textAlign: TextAlign.end, style: const TextStyle(fontWeight: FontWeight.w700))),
        ],
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.icon, required this.title, required this.text});

  final IconData icon;
  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(30),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(icon, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 14),
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 10),
          Text(text, style: Theme.of(context).textTheme.bodyLarge),
        ],
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.icon,
    required this.title,
    required this.text,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String text;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(30),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(icon, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 12),
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text(text),
          const SizedBox(height: 10),
          TextButton(onPressed: onAction, child: Text(actionLabel)),
        ],
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  const _StatCard({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label),
          const SizedBox(height: 10),
          Text(value, style: Theme.of(context).textTheme.headlineSmall),
        ],
      ),
    );
  }
}

String _formatBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  if (bytes < 1024) return '$bytes B';
  final kb = bytes / 1024;
  if (kb < 1024) return '${kb.toStringAsFixed(1)} KB';
  final mb = kb / 1024;
  return '${mb.toStringAsFixed(1)} MB';
}

String _formatDuration(Duration value) {
  final minutes = value.inMinutes;
  final seconds = value.inSeconds.remainder(60).toString().padLeft(2, '0');
  return '$minutes:$seconds';
}

