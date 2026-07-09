import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

const String sonorieVersion = '0.4.1-r2';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SonorieApp());
}

class AudioTrack {
  const AudioTrack({required this.title, required this.artist, required this.path, required this.folder, required this.extension, required this.sizeBytes});
  final String title;
  final String artist;
  final String path;
  final String folder;
  final String extension;
  final int sizeBytes;
}

class SonorieState extends ChangeNotifier {
  ThemeMode themeMode = ThemeMode.system;
  int pageIndex = 0;
  bool dockVisible = true;
  bool mediaPermissionGranted = false;
  bool scanning = false;
  String query = '';
  String? scanMessage;
  List<AudioTrack> tracks = <AudioTrack>[];
  Set<String> favoriteArtists = <String>{'Yeat'};
  Set<String> favoriteGenres = <String>{'Trap', 'Hip-hop'};

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    final themeValue = prefs.getString('sonorie_theme_mode') ?? 'system';
    themeMode = themeValue == 'dark' ? ThemeMode.dark : themeValue == 'light' ? ThemeMode.light : ThemeMode.system;
    favoriteArtists = (prefs.getStringList('favorite_artists') ?? <String>['Yeat']).toSet();
    favoriteGenres = (prefs.getStringList('favorite_genres') ?? <String>['Trap', 'Hip-hop']).toSet();
    mediaPermissionGranted = await _hasMediaPermission();
    notifyListeners();
    if (mediaPermissionGranted) unawaited(scanLocalMusic());
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    themeMode = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('sonorie_theme_mode', mode == ThemeMode.dark ? 'dark' : mode == ThemeMode.light ? 'light' : 'system');
    notifyListeners();
  }

  void setPage(int index) { pageIndex = index; notifyListeners(); }
  void toggleDock() { dockVisible = !dockVisible; notifyListeners(); }
  void setQuery(String value) { query = value; notifyListeners(); }

  Future<void> saveTaste(Set<String> artists, Set<String> genres) async {
    favoriteArtists = artists.isEmpty ? <String>{'Yeat'} : artists;
    favoriteGenres = genres.isEmpty ? <String>{'Trap', 'Hip-hop'} : genres;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('favorite_artists', favoriteArtists.toList());
    await prefs.setStringList('favorite_genres', favoriteGenres.toList());
    notifyListeners();
  }

  Future<void> requestMediaPermissionAndScan() async {
    scanning = true;
    scanMessage = 'Pedindo permissão de mídia...';
    notifyListeners();
    final granted = await _requestMediaPermission();
    mediaPermissionGranted = granted;
    if (!granted) {
      scanning = false;
      scanMessage = 'Permissão de mídia não concedida.';
      notifyListeners();
      return;
    }
    await scanLocalMusic();
  }

  Future<bool> _hasMediaPermission() async {
    if (!Platform.isAndroid) return true;
    final audioStatus = await Permission.audio.status;
    final storageStatus = await Permission.storage.status;
    return audioStatus.isGranted || audioStatus.isLimited || storageStatus.isGranted || storageStatus.isLimited;
  }

  Future<bool> _requestMediaPermission() async {
    if (!Platform.isAndroid) return true;
    final statuses = await <Permission>[Permission.audio, Permission.storage].request();
    final audio = statuses[Permission.audio];
    final storage = statuses[Permission.storage];
    return (audio?.isGranted ?? false) || (audio?.isLimited ?? false) || (storage?.isGranted ?? false) || (storage?.isLimited ?? false);
  }

  Future<void> scanLocalMusic() async {
    scanning = true;
    scanMessage = 'Varrendo músicas reais no aparelho...';
    notifyListeners();

    final found = <AudioTrack>[];
    final seen = <String>{};
    final roots = <String>{
      '/storage/emulated/0/Music', '/storage/emulated/0/Download', '/storage/emulated/0/Downloads', '/storage/emulated/0/Audio',
      '/storage/emulated/0/Recordings', '/storage/emulated/0/Ringtones', '/storage/emulated/0/Notifications', '/storage/emulated/0/Podcasts',
      '/storage/emulated/0/WhatsApp/Media/WhatsApp Audio', '/storage/emulated/0/WhatsApp/Media/WhatsApp Documents',
      '/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio', '/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents',
      '/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio',
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
      final storageRoot = Directory('/storage');
      if (await storageRoot.exists()) {
        await for (final entity in storageRoot.list(followLinks: false)) {
          if (entity is! Directory) continue;
          final base = entity.path;
          final name = base.split('/').last.toLowerCase();
          if (name == 'emulated' || name == 'self') continue;
          roots.add('$base/SnapTube Audio');
          roots.add('$base/Snaptube');
          roots.add('$base/Music');
          roots.add('$base/Download');
          roots.add('$base/Downloads');
          roots.add('$base/Audio');
        }
      }
    } catch (_) {
      // Alguns aparelhos bloqueiam listar /storage. O caminho fixo 3130-6234 continua como fallback.
    }

    const extensions = <String>{'mp3', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'flac', 'amr', '3gp'};

    for (final root in roots) {
      final dir = Directory(root);
      try {
        if (!await dir.exists()) continue;
        await for (final entity in dir.list(recursive: true, followLinks: false)) {
          if (entity is! File) continue;
          final path = entity.path;
          final ext = _extension(path);
          if (!extensions.contains(ext)) continue;
          if (!seen.add(path)) continue;
          int size = 0;
          try { size = await entity.length(); } catch (_) { size = 0; }
          if (size <= 0) continue;
          found.add(AudioTrack(title: _cleanTitle(path), artist: _guessArtist(path), path: path, folder: _folderName(path), extension: ext, sizeBytes: size));
          if (found.length % 25 == 0) {
            tracks = List<AudioTrack>.from(found)..sort(_trackSort);
            scanMessage = '${found.length} músicas encontradas...';
            notifyListeners();
          }
        }
      } catch (_) { continue; }
    }

    found.sort(_trackSort);
    tracks = found;
    scanning = false;
    scanMessage = found.isEmpty ? 'Nenhuma música encontrada. Tente Music, Download ou /storage/3130-6234/SnapTube Audio.' : '${found.length} músicas reais encontradas.';
    notifyListeners();
  }

  List<AudioTrack> get filteredTracks {
    final q = query.trim().toLowerCase();
    if (q.isEmpty) return tracks;
    return tracks.where((t) => t.title.toLowerCase().contains(q) || t.artist.toLowerCase().contains(q) || t.folder.toLowerCase().contains(q) || t.extension.toLowerCase().contains(q)).toList(growable: false);
  }

  static int _trackSort(AudioTrack a, AudioTrack b) {
    final byTitle = a.title.toLowerCase().compareTo(b.title.toLowerCase());
    return byTitle != 0 ? byTitle : a.path.compareTo(b.path);
  }
  static String _extension(String path) { final n = path.split('/').last; final dot = n.lastIndexOf('.'); return dot < 0 ? '' : n.substring(dot + 1).toLowerCase(); }
  static String _cleanTitle(String path) { var n = path.split('/').last; final dot = n.lastIndexOf('.'); if (dot > 0) n = n.substring(0, dot); return n.replaceAll(RegExp(r'[_]+'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim(); }
  static String _folderName(String path) { final p = path.split('/'); return p.length < 2 ? 'Local' : p[p.length - 2]; }
  static String _guessArtist(String path) { final f = _folderName(path); const bad = <String>{'Download','Downloads','Music','Audio','WhatsApp Audio','WhatsApp Documents','Recordings','Notifications','Ringtones','Podcasts'}; return bad.contains(f) ? '<unknown>' : f; }
}

class SonorieApp extends StatefulWidget { const SonorieApp({super.key}); @override State<SonorieApp> createState() => _SonorieAppState(); }
class _SonorieAppState extends State<SonorieApp> {
  late final SonorieState state = SonorieState();
  @override void initState() { super.initState(); unawaited(state.load()); }
  @override void dispose() { state.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) => AnimatedBuilder(animation: state, builder: (context, _) => MaterialApp(debugShowCheckedModeBanner: false, title: 'Sonorie', themeMode: state.themeMode, theme: SonorieTheme.light(), darkTheme: SonorieTheme.dark(), home: SonorieShell(state: state)));
}

class SonorieTheme {
  static const Color purple = Color(0xFFBFA7FF);
  static const Color darkBg = Color(0xFF100D16);
  static ThemeData dark() => ThemeData(useMaterial3: true, brightness: Brightness.dark, colorScheme: ColorScheme.fromSeed(brightness: Brightness.dark, seedColor: purple, surface: darkBg), scaffoldBackgroundColor: darkBg, fontFamily: 'sans', textTheme: const TextTheme(headlineLarge: TextStyle(fontWeight: FontWeight.w900), headlineMedium: TextStyle(fontWeight: FontWeight.w900), titleLarge: TextStyle(fontWeight: FontWeight.w900), bodyLarge: TextStyle(fontWeight: FontWeight.w500, height: 1.35)));
  static ThemeData light() => ThemeData(useMaterial3: true, brightness: Brightness.light, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF6B46C1)), fontFamily: 'sans');
}

class SonorieShell extends StatelessWidget {
  const SonorieShell({super.key, required this.state}); final SonorieState state;
  @override Widget build(BuildContext context) {
    final pages = [HomeScreen(state: state), LibraryScreen(state: state), PlayerScreen(state: state), SettingsScreen(state: state)];
    return Scaffold(body: Stack(children: [
      SafeArea(child: AnimatedPadding(duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic, padding: EdgeInsets.fromLTRB(22, 18, 22, state.dockVisible ? 138 : 46), child: IndexedStack(index: state.pageIndex, children: pages))),
      AnimatedPositioned(duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic, left: 22, right: 22, bottom: state.dockVisible ? 20 : -96, child: SonorieBottomDock(state: state)),
      AnimatedPositioned(duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic, left: 0, right: 0, bottom: state.dockVisible ? 126 : 18, child: Center(child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: state.toggleDock, child: Container(width: 92, height: 38, alignment: Alignment.center, child: Container(width: 72, height: 7, decoration: BoxDecoration(color: Theme.of(context).colorScheme.onSurface.withOpacity(0.48), borderRadius: BorderRadius.circular(999))))))),
    ]));
  }
}

class SonorieBottomDock extends StatelessWidget {
  const SonorieBottomDock({super.key, required this.state}); final SonorieState state;
  @override Widget build(BuildContext context) {
    final items = [const _DockItem('Início', Icons.home_rounded), const _DockItem('Biblioteca', Icons.music_note_rounded), const _DockItem('Player', Icons.play_circle_rounded), const _DockItem('Ajustes', Icons.settings_rounded)];
    return Material(color: Colors.transparent, child: Container(height: 104, decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.84), borderRadius: BorderRadius.circular(32), border: Border.all(color: Theme.of(context).colorScheme.outline.withOpacity(0.16)), boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.30), blurRadius: 32, offset: const Offset(0, 18))]), child: Row(children: List.generate(items.length, (i) { final selected = state.pageIndex == i; final item = items[i]; return Expanded(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 10), child: InkWell(borderRadius: BorderRadius.circular(26), onTap: () => state.setPage(i), child: AnimatedContainer(duration: const Duration(milliseconds: 180), decoration: BoxDecoration(color: selected ? Theme.of(context).colorScheme.primary.withOpacity(0.18) : Colors.transparent, borderRadius: BorderRadius.circular(26)), child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(item.icon, size: 32), const SizedBox(height: 7), Text(item.label, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800))]))))); }))));
  }
}
class _DockItem { const _DockItem(this.label, this.icon); final String label; final IconData icon; }

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.state}); final SonorieState state;
  @override Widget build(BuildContext context) {
    final primaryArtist = state.favoriteArtists.isEmpty ? 'seu gosto' : state.favoriteArtists.first;
    final latest = state.tracks.take(4).toList();
    return ListView(padding: EdgeInsets.zero, children: [
      const SonorieHeader(subtitle: 'Biblioteca Flutter real'), const SizedBox(height: 22),
      GradientHeroCard(title: 'Sua biblioteca local, com visual moderno.', text: state.mediaPermissionGranted ? '${state.tracks.length} músicas reais encontradas. A leitura local agora já é Flutter.' : 'Permita acesso às músicas para o Sonorie varrer arquivos reais do aparelho.', button: state.mediaPermissionGranted ? 'Abrir biblioteca' : 'Permitir músicas', icon: Icons.music_note_rounded, onTap: () { if (state.mediaPermissionGranted) { state.setPage(1); } else { unawaited(state.requestMediaPermissionAndScan()); }}),
      const SizedBox(height: 18), TasteSummaryCard(state: state), const SizedBox(height: 26), const SectionTitle('Para você'),
      InfoCard(icon: Icons.auto_awesome_rounded, title: 'Descobertas para $primaryArtist', text: 'O Sonorie usa artistas, gêneros e músicas locais para organizar cápsulas. Descoberta online entra depois com APIs oficiais.', chips: state.favoriteGenres.take(3).toList()),
      const SizedBox(height: 26), const SectionTitle('Resumo local'), Row(children: [Expanded(child: SmallStatCard(label: 'Músicas', value: state.scanning ? 'Varrendo' : '${state.tracks.length}')), const SizedBox(width: 14), const Expanded(child: SmallStatCard(label: 'Modo', value: 'Flutter'))]),
      const SizedBox(height: 26), const SectionTitle('Últimas músicas encontradas'),
      if (state.scanning) Padding(padding: const EdgeInsets.only(top: 8), child: Text(state.scanMessage ?? 'Varrendo...')) else if (latest.isEmpty) EmptyRealState(icon: Icons.folder_open_rounded, title: 'Nenhuma música carregada ainda', text: state.mediaPermissionGranted ? 'Toque em Biblioteca e atualize a varredura.' : 'Permita músicas para listar arquivos reais. Nada será simulado.', action: state.mediaPermissionGranted ? 'Abrir biblioteca' : 'Permitir músicas', onTap: () { if (state.mediaPermissionGranted) { state.setPage(1); } else { unawaited(state.requestMediaPermissionAndScan()); }}) else ...latest.map((t) => MiniTrackTile(track: t)),
    ]);
  }
}

class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key, required this.state}); final SonorieState state;
  @override Widget build(BuildContext context) {
    final tracks = state.filteredTracks;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SonorieHeader(subtitle: 'Biblioteca local real'), const SizedBox(height: 22), Text('Biblioteca local', style: Theme.of(context).textTheme.headlineMedium), const SizedBox(height: 4),
      Text(state.scanning ? (state.scanMessage ?? 'Varrendo músicas...') : '${state.tracks.length} músicas reais encontradas no aparelho', style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withOpacity(0.72))), const SizedBox(height: 16),
      if (!state.mediaPermissionGranted) PrimaryPillButton(label: 'Permitir músicas', icon: Icons.folder_rounded, onTap: () => unawaited(state.requestMediaPermissionAndScan())) else Row(children: [PrimaryPillButton(label: state.scanning ? 'Varrendo...' : 'Atualizar', icon: Icons.refresh_rounded, onTap: state.scanning ? null : () => unawaited(state.scanLocalMusic())), const SizedBox(width: 12), Text('Leitura real', style: TextStyle(fontWeight: FontWeight.w800, color: Theme.of(context).colorScheme.primary))]),
      const SizedBox(height: 18), TextField(onChanged: state.setQuery, decoration: InputDecoration(hintText: 'Buscar música, pasta ou formato', prefixIcon: const Icon(Icons.search_rounded), border: OutlineInputBorder(borderRadius: BorderRadius.circular(26)))), const SizedBox(height: 14),
      Expanded(child: Builder(builder: (context) {
        if (state.scanning && tracks.isEmpty) return const Center(child: CircularProgressIndicator());
        if (!state.mediaPermissionGranted) return EmptyRealState(icon: Icons.privacy_tip_rounded, title: 'Permissão necessária', text: 'O Sonorie precisa da permissão de mídia para ler músicas reais. Sem permissão, ele não inventa biblioteca.', action: 'Permitir músicas', onTap: () => unawaited(state.requestMediaPermissionAndScan()));
        if (tracks.isEmpty) return EmptyRealState(icon: Icons.music_off_rounded, title: 'Nada encontrado', text: state.query.trim().isEmpty ? 'Não encontrei áudio nas pastas públicas nem no SnapTube externo. Se o Android bloquear o cartão, a próxima etapa terá seletor de pasta SAF.' : 'Nenhum arquivo real combina com sua busca.', action: 'Atualizar varredura', onTap: () => unawaited(state.scanLocalMusic()));
        return ListView.builder(padding: const EdgeInsets.only(bottom: 24), itemCount: tracks.length, itemBuilder: (context, i) => RealTrackTile(track: tracks[i]));
      })),
    ]);
  }
}

class PlayerScreen extends StatelessWidget { const PlayerScreen({super.key, required this.state}); final SonorieState state; @override Widget build(BuildContext context) => ListView(padding: EdgeInsets.zero, children: [const SonorieHeader(subtitle: 'Player em migração'), const SizedBox(height: 34), Container(padding: const EdgeInsets.all(32), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.70), borderRadius: BorderRadius.circular(32)), child: Column(children: [Icon(Icons.play_circle_outline_rounded, size: 86, color: Theme.of(context).colorScheme.primary), const SizedBox(height: 22), Text('Player real ainda não conectado', textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)), const SizedBox(height: 14), Text('A biblioteca real já começou. A próxima etapa liga áudio real, fila, progresso e notificação sem simulação.', textAlign: TextAlign.center)])), const SizedBox(height: 24), InfoCard(icon: Icons.check_circle_outline_rounded, title: 'Sem player falso', text: '${state.tracks.length} músicas reais podem ser listadas, mas tocar fica para o módulo real do player.')]); }

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.state}); final SonorieState state;
  @override Widget build(BuildContext context) => ListView(padding: EdgeInsets.zero, children: [const SonorieHeader(subtitle: 'Ajustes'), const SizedBox(height: 22), Text('Aparência', style: Theme.of(context).textTheme.headlineSmall), const SizedBox(height: 12), ThemeButton(label: 'Usar tema do sistema', selected: state.themeMode == ThemeMode.system, onTap: () => unawaited(state.setThemeMode(ThemeMode.system))), ThemeButton(label: 'Modo claro', selected: state.themeMode == ThemeMode.light, onTap: () => unawaited(state.setThemeMode(ThemeMode.light))), ThemeButton(label: 'Modo escuro', selected: state.themeMode == ThemeMode.dark, onTap: () => unawaited(state.setThemeMode(ThemeMode.dark))), const SizedBox(height: 26), Text('Status do app', style: Theme.of(context).textTheme.headlineSmall), const SizedBox(height: 16), StatusCard(rows: [const MapEntry('Base', 'Flutter real'), const MapEntry('Versão', 'v$sonorieVersion'), MapEntry('Tema', state.themeMode == ThemeMode.system ? 'Sistema' : state.themeMode == ThemeMode.dark ? 'Escuro' : 'Claro'), MapEntry('Permissão de mídia', state.mediaPermissionGranted ? 'Permitida' : 'Pendente'), MapEntry('Biblioteca', state.scanning ? 'Varrendo' : '${state.tracks.length} músicas'), const MapEntry('Player', 'Em migração'), const MapEntry('Dock inferior', 'Toggle por clique'), const MapEntry('Imagens de artista', 'Somente real/segura')]), const SizedBox(height: 20), PrimaryPillButton(label: 'Editar gosto musical', icon: Icons.favorite_rounded, onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => TasteOnboardingScreen(state: state)))), const SizedBox(height: 18), PrimaryPillButton(label: state.scanning ? 'Varrendo músicas...' : 'Atualizar biblioteca real', icon: Icons.refresh_rounded, onTap: state.scanning ? null : () => unawaited(state.requestMediaPermissionAndScan())), const SizedBox(height: 24), const InfoCard(icon: Icons.route_rounded, title: 'Próxima evolução', text: 'v0.4.2: conectar player real com áudio local, progresso, fila e notificação.')]);
}

class TasteOnboardingScreen extends StatefulWidget { const TasteOnboardingScreen({super.key, required this.state}); final SonorieState state; @override State<TasteOnboardingScreen> createState() => _TasteOnboardingScreenState(); }
class _TasteOnboardingScreenState extends State<TasteOnboardingScreen> {
  late Set<String> artists = Set<String>.from(widget.state.favoriteArtists); late Set<String> genres = Set<String>.from(widget.state.favoriteGenres);
  static const featuredArtists = <String>['Yeat','Ken Carson','Playboi Carti','Nine Vicious','Destroy Lonely','Homixide Gang','Travis Scott','Future','Lil Uzi Vert','21 Savage','Drake','The Weeknd','Kendrick Lamar','Tyler, The Creator','A\$AP Rocky','Metro Boomin','SZA','Doja Cat','Billie Eilish','Ariana Grande','Dua Lipa','Bruno Mars','Post Malone','Bad Bunny','Anitta','Matue','Teto','Wiu','Veigh','MC Cabelinho','Luan Santana','Gusttavo Lima'];
  static const genreOptions = <String>['Rage','Trap','Hip-hop','Pluggnb','Rap','R&B','Pop','Funk','Phonk','Eletrônica','Lo-fi','Rock','Indie','Metal','Sertanejo','Gospel','Jazz','MPB','Reggaeton','Afrobeat','K-pop','Hyperpop','Drill','House'];
  void toggle(Set<String> set, String value) { setState(() { if (!set.add(value)) set.remove(value); }); }
  @override Widget build(BuildContext context) => Scaffold(body: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(22, 18, 22, 28), children: [Text('Sonorie', style: Theme.of(context).textTheme.headlineLarge), const SizedBox(height: 10), Text('Monte seu gosto musical', style: Theme.of(context).textTheme.headlineSmall), const SizedBox(height: 10), const Text('Escolha artistas e gêneros para organizar cápsulas, recomendações locais e a home do seu jeito.'), const SizedBox(height: 22), const GradientHeroCard(title: 'Descoberta personalizada', text: 'Essa é a base local. Imagens de artista só entram quando forem reais e seguras.', button: null, icon: Icons.auto_awesome_rounded), const SizedBox(height: 24), const SectionTitle('Artistas favoritos'), Wrap(spacing: 12, runSpacing: 12, children: [for (final a in featuredArtists) TasteChip(label: a, selected: artists.contains(a), onTap: () => toggle(artists, a))]), const SizedBox(height: 28), const SectionTitle('Gêneros musicais'), Wrap(spacing: 12, runSpacing: 12, children: [for (final g in genreOptions) TasteChip(label: g, selected: genres.contains(g), onTap: () => toggle(genres, g))]), const SizedBox(height: 30), PrimaryPillButton(label: 'Entrar no Sonorie', icon: Icons.favorite_rounded, onTap: () async { await widget.state.saveTaste(artists, genres); if (context.mounted) Navigator.of(context).pop(); }), const SizedBox(height: 16), TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Pular por enquanto'))])));
}

class SonorieHeader extends StatelessWidget { const SonorieHeader({super.key, required this.subtitle}); final String subtitle; @override Widget build(BuildContext context) => Column(children: [Text('Sonorie', style: Theme.of(context).textTheme.headlineLarge), const SizedBox(height: 4), Text(subtitle, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Theme.of(context).colorScheme.onSurface.withOpacity(0.70), fontWeight: FontWeight.w800))]); }
class GradientHeroCard extends StatelessWidget { const GradientHeroCard({super.key, required this.title, required this.text, required this.icon, this.button, this.onTap}); final String title; final String text; final IconData icon; final String? button; final VoidCallback? onTap; @override Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(28), decoration: BoxDecoration(borderRadius: BorderRadius.circular(32), gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF5D3BA6), Color(0xFF4F4860), Color(0xFF7A4056)])), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [CircleAvatar(radius: 31, backgroundColor: Colors.black.withOpacity(0.30), child: Icon(icon, size: 34, color: const Color(0xFFD9C7FF))), const SizedBox(height: 26), Text(title, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)), const SizedBox(height: 18), Text(text, style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: Colors.white.withOpacity(0.72))), if (button != null) ...[const SizedBox(height: 24), PrimaryPillButton(label: button!, icon: Icons.play_arrow_rounded, onTap: onTap)]])); }
class TasteSummaryCard extends StatelessWidget { const TasteSummaryCard({super.key, required this.state}); final SonorieState state; @override Widget build(BuildContext context) { final artists = state.favoriteArtists.join(', '); final genres = state.favoriteGenres.join(', '); return Container(padding: const EdgeInsets.all(26), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.78), borderRadius: BorderRadius.circular(30)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Icon(Icons.favorite_rounded, color: Theme.of(context).colorScheme.primary, size: 34), const SizedBox(height: 14), Text('Seu gosto musical', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 10), Text(artists.isEmpty ? 'Ainda não escolhido' : artists), if (genres.isNotEmpty) Text(genres, style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withOpacity(0.55))), const SizedBox(height: 16), InkWell(onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => TasteOnboardingScreen(state: state))), child: Text('Editar gosto musical', style: TextStyle(fontWeight: FontWeight.w900, color: Theme.of(context).colorScheme.primary))) ])); }}
class InfoCard extends StatelessWidget { const InfoCard({super.key, required this.icon, required this.title, required this.text, this.chips = const <String>[]}); final IconData icon; final String title; final String text; final List<String> chips; @override Widget build(BuildContext context) => Container(margin: const EdgeInsets.only(top: 10), padding: const EdgeInsets.all(24), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surface.withOpacity(0.30), borderRadius: BorderRadius.circular(28), border: Border.all(color: Theme.of(context).colorScheme.outline.withOpacity(0.28))), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Icon(icon, color: Theme.of(context).colorScheme.primary, size: 30), const SizedBox(height: 14), Text(title, style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 12), Text(text), if (chips.isNotEmpty) ...[const SizedBox(height: 18), Wrap(spacing: 10, runSpacing: 10, children: [for (final c in chips) Chip(label: Text(c))])]])); }
class SmallStatCard extends StatelessWidget { const SmallStatCard({super.key, required this.label, required this.value}); final String label; final String value; @override Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(22), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.46), borderRadius: BorderRadius.circular(26)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label), const SizedBox(height: 12), Text(value, style: Theme.of(context).textTheme.titleLarge)])); }
class SectionTitle extends StatelessWidget { const SectionTitle(this.text, {super.key}); final String text; @override Widget build(BuildContext context) => Text(text, style: Theme.of(context).textTheme.titleLarge); }
class PrimaryPillButton extends StatelessWidget { const PrimaryPillButton({super.key, required this.label, required this.icon, this.onTap}); final String label; final IconData icon; final VoidCallback? onTap; @override Widget build(BuildContext context) => InkWell(onTap: onTap, borderRadius: BorderRadius.circular(999), child: Opacity(opacity: onTap == null ? 0.55 : 1, child: Container(padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 13), decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withOpacity(0.82), borderRadius: BorderRadius.circular(999)), child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 20, color: Theme.of(context).colorScheme.onPrimary), const SizedBox(width: 10), Text(label, style: TextStyle(color: Theme.of(context).colorScheme.onPrimary, fontWeight: FontWeight.w900))])))); }
class ThemeButton extends StatelessWidget { const ThemeButton({super.key, required this.label, required this.selected, required this.onTap}); final String label; final bool selected; final VoidCallback onTap; @override Widget build(BuildContext context) => Padding(padding: const EdgeInsets.only(bottom: 12), child: InkWell(borderRadius: BorderRadius.circular(999), onTap: onTap, child: AnimatedContainer(duration: const Duration(milliseconds: 160), padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 17), decoration: BoxDecoration(color: selected ? Theme.of(context).colorScheme.primary.withOpacity(0.16) : Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.64), borderRadius: BorderRadius.circular(999), border: Border.all(color: selected ? Theme.of(context).colorScheme.primary.withOpacity(0.70) : Colors.transparent)), child: Center(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w900)))))); }
class TasteChip extends StatelessWidget { const TasteChip({super.key, required this.label, required this.selected, required this.onTap}); final String label; final bool selected; final VoidCallback onTap; @override Widget build(BuildContext context) => SizedBox(width: 150, child: InkWell(borderRadius: BorderRadius.circular(999), onTap: onTap, child: AnimatedContainer(duration: const Duration(milliseconds: 150), padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13), decoration: BoxDecoration(color: selected ? Theme.of(context).colorScheme.primary.withOpacity(0.32) : Colors.transparent, borderRadius: BorderRadius.circular(999), border: Border.all(color: selected ? Theme.of(context).colorScheme.primary.withOpacity(0.76) : Theme.of(context).colorScheme.outline.withOpacity(0.56))), child: Row(children: [Icon(Icons.favorite_border_rounded, size: 22, color: Theme.of(context).colorScheme.primary), const SizedBox(width: 8), Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)))])))); }
class MiniTrackTile extends StatelessWidget { const MiniTrackTile({super.key, required this.track}); final AudioTrack track; @override Widget build(BuildContext context) => Container(margin: const EdgeInsets.only(top: 12), child: Row(children: [Icon(Icons.music_note_rounded, color: Theme.of(context).colorScheme.primary), const SizedBox(width: 16), Expanded(child: Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)))])); }
class RealTrackTile extends StatelessWidget { const RealTrackTile({super.key, required this.track}); final AudioTrack track; @override Widget build(BuildContext context) { final sizeMb = track.sizeBytes / (1024 * 1024); return Container(margin: const EdgeInsets.only(bottom: 14), padding: const EdgeInsets.all(18), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.68), borderRadius: BorderRadius.circular(28)), child: Row(children: [CircleAvatar(radius: 27, backgroundColor: Theme.of(context).colorScheme.primary.withOpacity(0.17), child: Icon(Icons.music_note_rounded, color: Theme.of(context).colorScheme.primary)), const SizedBox(width: 16), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)), const SizedBox(height: 4), Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis), const SizedBox(height: 4), Text('${track.extension.toUpperCase()} • ${sizeMb.toStringAsFixed(1)} MB • ${track.folder}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12))])), const SizedBox(width: 12), const Icon(Icons.lock_clock_rounded)])); }}
class EmptyRealState extends StatelessWidget { const EmptyRealState({super.key, required this.icon, required this.title, required this.text, required this.action, required this.onTap}); final IconData icon; final String title; final String text; final String action; final VoidCallback onTap; @override Widget build(BuildContext context) => Center(child: Container(padding: const EdgeInsets.all(28), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surface.withOpacity(0.30), borderRadius: BorderRadius.circular(28), border: Border.all(color: Theme.of(context).colorScheme.outline.withOpacity(0.24))), child: Column(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 56, color: Theme.of(context).colorScheme.primary), const SizedBox(height: 16), Text(title, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 10), Text(text, textAlign: TextAlign.center), const SizedBox(height: 18), PrimaryPillButton(label: action, icon: Icons.arrow_forward_rounded, onTap: onTap)]))); }
class StatusCard extends StatelessWidget { const StatusCard({super.key, required this.rows}); final List<MapEntry<String, String>> rows; @override Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(22), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surface.withOpacity(0.24), borderRadius: BorderRadius.circular(28), border: Border.all(color: Theme.of(context).colorScheme.outline.withOpacity(0.24))), child: Column(children: [for (final row in rows) Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: Row(children: [Expanded(child: Text(row.key)), Flexible(child: Text(row.value, textAlign: TextAlign.end, style: const TextStyle(fontWeight: FontWeight.w900))) ]))])); }
