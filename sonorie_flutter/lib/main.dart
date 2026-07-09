import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

const String sonorieVersion = '0.4.0-r1';

void main() {
  runApp(const SonorieFlutterApp());
}

class SonorieFlutterApp extends StatefulWidget {
  const SonorieFlutterApp({super.key});

  @override
  State<SonorieFlutterApp> createState() => _SonorieFlutterAppState();
}

class _SonorieFlutterAppState extends State<SonorieFlutterApp> {
  ThemeMode _themeMode = ThemeMode.system;
  Set<String> _favoriteArtists = <String>{};
  Set<String> _favoriteGenres = <String>{};
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _loadPrefs();
  }

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    final mode = prefs.getString('sonorie_theme_mode') ?? 'system';
    setState(() {
      _themeMode = switch (mode) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      };
      _favoriteArtists = (prefs.getStringList('sonorie_favorite_artists') ?? <String>[]).toSet();
      _favoriteGenres = (prefs.getStringList('sonorie_favorite_genres') ?? <String>[]).toSet();
      _loaded = true;
    });
  }

  Future<void> _setThemeMode(ThemeMode mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      'sonorie_theme_mode',
      switch (mode) {
        ThemeMode.light => 'light',
        ThemeMode.dark => 'dark',
        ThemeMode.system => 'system',
      },
    );
    setState(() => _themeMode = mode);
  }

  Future<void> _saveTaste(Set<String> artists, Set<String> genres) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('sonorie_favorite_artists', artists.toList());
    await prefs.setStringList('sonorie_favorite_genres', genres.toList());
    setState(() {
      _favoriteArtists = artists;
      _favoriteGenres = genres;
    });
  }

  @override
  Widget build(BuildContext context) {
    final seed = const Color(0xFFBFA6FF);
    final darkScheme = ColorScheme.fromSeed(
      seedColor: seed,
      brightness: Brightness.dark,
    ).copyWith(
      surface: const Color(0xFF100D14),
      surfaceContainerHighest: const Color(0xFF4E485F),
      primary: const Color(0xFFCDB8FF),
      secondary: const Color(0xFF8D72D9),
    );

    final lightScheme = ColorScheme.fromSeed(
      seedColor: seed,
      brightness: Brightness.light,
    );

    if (!_loaded) {
      return MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: ThemeData(useMaterial3: true, colorScheme: darkScheme),
        home: const Scaffold(
          body: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    final needsOnboarding = _favoriteArtists.isEmpty && _favoriteGenres.isEmpty;

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Sonorie',
      themeMode: _themeMode,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: lightScheme,
        scaffoldBackgroundColor: lightScheme.surface,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: darkScheme,
        scaffoldBackgroundColor: const Color(0xFF100D14),
      ),
      home: needsOnboarding
          ? TasteOnboardingScreen(
              onFinish: _saveTaste,
              onSkip: () => _saveTaste(<String>{}, <String>{}),
            )
          : SonorieShell(
              themeMode: _themeMode,
              favoriteArtists: _favoriteArtists,
              favoriteGenres: _favoriteGenres,
              onThemeChanged: _setThemeMode,
              onEditTaste: () async {
                await Navigator.of(context).push<void>(
                  MaterialPageRoute(
                    builder: (_) => TasteOnboardingScreen(
                      initialArtists: _favoriteArtists,
                      initialGenres: _favoriteGenres,
                      onFinish: (artists, genres) async {
                        await _saveTaste(artists, genres);
                        if (context.mounted) {
                          Navigator.of(context).pop();
                        }
                      },
                      onSkip: () {
                        Navigator.of(context).pop();
                      },
                    ),
                  ),
                );
              },
            ),
    );
  }
}

class SonorieShell extends StatefulWidget {
  const SonorieShell({
    super.key,
    required this.themeMode,
    required this.favoriteArtists,
    required this.favoriteGenres,
    required this.onThemeChanged,
    required this.onEditTaste,
  });

  final ThemeMode themeMode;
  final Set<String> favoriteArtists;
  final Set<String> favoriteGenres;
  final ValueChanged<ThemeMode> onThemeChanged;
  final VoidCallback onEditTaste;

  @override
  State<SonorieShell> createState() => _SonorieShellState();
}

class _SonorieShellState extends State<SonorieShell> {
  int _tab = 0;
  bool _dockExpanded = true;

  void _toggleDock() {
    setState(() => _dockExpanded = !_dockExpanded);
  }

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      HomeScreen(
        favoriteArtists: widget.favoriteArtists,
        favoriteGenres: widget.favoriteGenres,
        onOpenLibrary: () => setState(() => _tab = 1),
        onEditTaste: widget.onEditTaste,
      ),
      const LibraryScreen(),
      const PlayerScreen(),
      SettingsScreen(
        themeMode: widget.themeMode,
        onThemeChanged: widget.onThemeChanged,
        onEditTaste: widget.onEditTaste,
      ),
    ];

    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            child: AnimatedPadding(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              padding: EdgeInsets.only(bottom: _dockExpanded ? 154 : 42),
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 220),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeInCubic,
                child: KeyedSubtree(
                  key: ValueKey<int>(_tab),
                  child: pages[_tab],
                ),
              ),
            ),
          ),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
            left: 0,
            right: 0,
            bottom: _dockExpanded ? 116 : 16,
            child: Center(
              child: BottomDockHandle(
                expanded: _dockExpanded,
                onTap: _toggleDock,
              ),
            ),
          ),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
            left: 20,
            right: 20,
            bottom: _dockExpanded ? 14 : -112,
            child: SonorieBottomDock(
              currentIndex: _tab,
              onTabSelected: (index) => setState(() => _tab = index),
            ),
          ),
        ],
      ),
    );
  }
}

class BottomDockHandle extends StatelessWidget {
  const BottomDockHandle({
    super.key,
    required this.expanded,
    required this.onTap,
  });

  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: expanded ? 'Esconder barra inferior' : 'Mostrar barra inferior',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            width: expanded ? 64 : 52,
            height: 6,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.onSurfaceVariant.withOpacity(0.55),
              borderRadius: BorderRadius.circular(99),
            ),
          ),
        ),
      ),
    );
  }
}

class SonorieBottomDock extends StatelessWidget {
  const SonorieBottomDock({
    super.key,
    required this.currentIndex,
    required this.onTabSelected,
  });

  final int currentIndex;
  final ValueChanged<int> onTabSelected;

  static const _items = [
    _DockItem(Icons.home_rounded, 'Início'),
    _DockItem(Icons.music_note_rounded, 'Biblioteca'),
    _DockItem(Icons.play_circle_rounded, 'Player'),
    _DockItem(Icons.settings_rounded, 'Ajustes'),
  ];

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest.withOpacity(0.86),
          borderRadius: BorderRadius.circular(30),
          border: Border.all(
            color: scheme.outlineVariant.withOpacity(0.42),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.22),
              blurRadius: 24,
              offset: const Offset(0, 12),
            ),
          ],
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              for (var i = 0; i < _items.length; i++)
                _DockButton(
                  item: _items[i],
                  selected: i == currentIndex,
                  onTap: () => onTabSelected(i),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DockItem {
  const _DockItem(this.icon, this.label);
  final IconData icon;
  final String label;
}

class _DockButton extends StatelessWidget {
  const _DockButton({
    required this.item,
    required this.selected,
    required this.onTap,
  });

  final _DockItem item;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Expanded(
      child: InkWell(
        borderRadius: BorderRadius.circular(22),
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: selected ? scheme.primary.withOpacity(0.16) : Colors.transparent,
            borderRadius: BorderRadius.circular(22),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                item.icon,
                color: selected ? scheme.onSurface : scheme.onSurfaceVariant,
                size: 30,
              ),
              const SizedBox(height: 5),
              Text(
                item.label,
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                      color: selected ? scheme.onSurface : scheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.favoriteArtists,
    required this.favoriteGenres,
    required this.onOpenLibrary,
    required this.onEditTaste,
  });

  final Set<String> favoriteArtists;
  final Set<String> favoriteGenres;
  final VoidCallback onOpenLibrary;
  final VoidCallback onEditTaste;

  @override
  Widget build(BuildContext context) {
    final artists = favoriteArtists.toList();
    final genres = favoriteGenres.toList();
    final primaryArtist = artists.isNotEmpty ? artists.first : 'Seu artista favorito';

    return SonoriePage(
      subtitle: 'Música offline com alma',
      children: [
        HeroLibraryCard(onOpenLibrary: onOpenLibrary),
        const SizedBox(height: 20),
        TasteSummaryCard(
          artists: artists,
          genres: genres,
          onEditTaste: onEditTaste,
        ),
        const SizedBox(height: 22),
        SectionLabel('Para você'),
        const SizedBox(height: 12),
        RecommendationCard(
          title: 'Descobertas para $primaryArtist',
          description: 'O Sonorie vai usar seus artistas, gêneros e músicas locais para organizar cápsulas. Descoberta online entra depois com APIs oficiais.',
          genres: genres.take(2).toList(),
        ),
        const SizedBox(height: 22),
        SectionLabel('Resumo local'),
        const SizedBox(height: 12),
        Row(
          children: const [
            Expanded(child: StatCard(label: 'Músicas', value: 'Em migração')),
            SizedBox(width: 14),
            Expanded(child: StatCard(label: 'Modo', value: 'Flutter')),
          ],
        ),
        const SizedBox(height: 22),
        SectionLabel('Cápsulas'),
        const SizedBox(height: 12),
        HonestFeatureCard(
          icon: Icons.music_note_rounded,
          title: 'Cápsulas musicais inteligentes',
          body: 'Base Flutter criada. A leitura real das músicas locais e o player serão conectados nas próximas etapas, sem tela falsa.',
        ),
      ],
    );
  }
}

class HeroLibraryCard extends StatelessWidget {
  const HeroLibraryCard({super.key, required this.onOpenLibrary});
  final VoidCallback onOpenLibrary;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(28),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(32),
        gradient: LinearGradient(
          colors: [
            scheme.primary.withOpacity(0.35),
            const Color(0xFF7C4458).withOpacity(0.58),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 30,
            backgroundColor: Colors.black.withOpacity(0.25),
            child: Icon(
              Icons.music_note_rounded,
              color: scheme.primary,
              size: 34,
            ),
          ),
          const SizedBox(height: 26),
          Text(
            'Sua biblioteca local, com visual moderno.',
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w900,
                  color: scheme.onSurface,
                  height: 1.15,
                ),
          ),
          const SizedBox(height: 16),
          Text(
            'Esta é a primeira base Flutter real. A leitura offline real será reconectada por módulo, sem maquiar função.',
            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: scheme.onSurfaceVariant,
                  height: 1.35,
                ),
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: onOpenLibrary,
            icon: const Icon(Icons.play_arrow_rounded),
            label: const Text('Abrir biblioteca'),
          ),
        ],
      ),
    );
  }
}

class TasteSummaryCard extends StatelessWidget {
  const TasteSummaryCard({
    super.key,
    required this.artists,
    required this.genres,
    required this.onEditTaste,
  });

  final List<String> artists;
  final List<String> genres;
  final VoidCallback onEditTaste;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final line = artists.isEmpty ? 'Sem artistas escolhidos ainda' : artists.take(5).join(', ');
    return InkWell(
      borderRadius: BorderRadius.circular(30),
      onTap: onEditTaste,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest.withOpacity(0.76),
          borderRadius: BorderRadius.circular(30),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.favorite_rounded, color: scheme.primary, size: 30),
            const SizedBox(height: 10),
            Text(
              'Seu gosto musical',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
            ),
            const SizedBox(height: 8),
            Text(
              line,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    color: scheme.onSurfaceVariant,
                    height: 1.35,
                  ),
            ),
            if (genres.isNotEmpty) ...[
              const SizedBox(height: 14),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final genre in genres.take(4)) TastePill(label: genre),
                ],
              ),
            ],
            const SizedBox(height: 14),
            Text(
              'Editar gosto musical',
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: scheme.primary,
                    fontWeight: FontWeight.w900,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class RecommendationCard extends StatelessWidget {
  const RecommendationCard({
    super.key,
    required this.title,
    required this.description,
    required this.genres,
  });

  final String title;
  final String description;
  final List<String> genres;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: scheme.surface.withOpacity(0.66),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: scheme.outlineVariant.withOpacity(0.7)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.auto_awesome_rounded, color: scheme.primary),
          const SizedBox(height: 10),
          Text(
            title,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w900,
                ),
          ),
          const SizedBox(height: 12),
          Text(
            description,
            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: scheme.onSurfaceVariant,
                  height: 1.45,
                ),
          ),
          if (genres.isNotEmpty) ...[
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final genre in genres) TastePill(label: genre),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return SonoriePage(
      subtitle: 'Biblioteca Flutter',
      children: const [
        SectionLabel('Biblioteca local'),
        SizedBox(height: 12),
        HonestFeatureCard(
          icon: Icons.folder_music_rounded,
          title: 'Leitura offline em migração',
          body: 'Esta tela já é Flutter real, mas ainda não lista nem toca músicas. O próximo módulo vai conectar permissão de mídia e varredura local real.',
        ),
        SizedBox(height: 16),
        HonestFeatureCard(
          icon: Icons.tune_rounded,
          title: 'Sem player falso',
          body: 'Enquanto o motor real não for migrado, o Sonorie não mostra lista falsa nem controles que fingem tocar música.',
        ),
      ],
    );
  }
}

class PlayerScreen extends StatelessWidget {
  const PlayerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SonoriePage(
      subtitle: 'Player em migração',
      children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(28),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(32),
            color: scheme.surfaceContainerHighest.withOpacity(0.72),
          ),
          child: Column(
            children: [
              Icon(Icons.play_circle_outline_rounded, size: 84, color: scheme.primary),
              const SizedBox(height: 18),
              Text(
                'Player real ainda não conectado',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
              ),
              const SizedBox(height: 12),
              Text(
                'A base Flutter está pronta. A próxima etapa liga áudio real, fila, progresso e notificação sem simulação.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      color: scheme.onSurfaceVariant,
                      height: 1.4,
                    ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({
    super.key,
    required this.themeMode,
    required this.onThemeChanged,
    required this.onEditTaste,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeChanged;
  final VoidCallback onEditTaste;

  @override
  Widget build(BuildContext context) {
    return SonoriePage(
      subtitle: 'Ajustes',
      children: [
        SectionLabel('Aparência'),
        const SizedBox(height: 12),
        ThemeButton(
          label: 'Usar tema do sistema',
          selected: themeMode == ThemeMode.system,
          onTap: () => onThemeChanged(ThemeMode.system),
        ),
        const SizedBox(height: 10),
        ThemeButton(
          label: 'Modo claro',
          selected: themeMode == ThemeMode.light,
          onTap: () => onThemeChanged(ThemeMode.light),
        ),
        const SizedBox(height: 10),
        ThemeButton(
          label: 'Modo escuro',
          selected: themeMode == ThemeMode.dark,
          onTap: () => onThemeChanged(ThemeMode.dark),
        ),
        const SizedBox(height: 24),
        SectionLabel('Status do app'),
        const SizedBox(height: 12),
        SettingsCard(
          rows: const [
            ('Base', 'Flutter real'),
            ('Versão', 'v0.4.0-r1'),
            ('Tema', 'Persistente'),
            ('Player', 'Em migração'),
            ('Biblioteca', 'Em migração'),
            ('Imagem de artista', 'Somente real/segura'),
            ('Dock inferior', 'Toggle por clique'),
          ],
        ),
        const SizedBox(height: 18),
        FilledButton.icon(
          onPressed: onEditTaste,
          icon: const Icon(Icons.favorite_rounded),
          label: const Text('Editar gosto musical'),
        ),
        const SizedBox(height: 20),
        const HonestFeatureCard(
          icon: Icons.route_rounded,
          title: 'Próxima evolução',
          body: 'v0.4.1: conectar permissão de mídia e leitura real das músicas locais no Flutter.',
        ),
      ],
    );
  }
}

class TasteOnboardingScreen extends StatefulWidget {
  const TasteOnboardingScreen({
    super.key,
    required this.onFinish,
    required this.onSkip,
    this.initialArtists = const <String>{},
    this.initialGenres = const <String>{},
  });

  final Set<String> initialArtists;
  final Set<String> initialGenres;
  final Future<void> Function(Set<String> artists, Set<String> genres) onFinish;
  final VoidCallback onSkip;

  @override
  State<TasteOnboardingScreen> createState() => _TasteOnboardingScreenState();
}

class _TasteOnboardingScreenState extends State<TasteOnboardingScreen> {
  late Set<String> selectedArtists;
  late Set<String> selectedGenres;

  final artists = const [
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

  final genres = const [
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

  @override
  void initState() {
    super.initState();
    selectedArtists = widget.initialArtists.toSet();
    selectedGenres = widget.initialGenres.toSet();
  }

  void toggle(Set<String> target, String value) {
    setState(() {
      if (target.contains(value)) {
        target.remove(value);
      } else {
        target.add(value);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final canEnter = selectedArtists.isNotEmpty || selectedGenres.isNotEmpty;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(28, 22, 28, 28),
          children: [
            Text(
              'Sonorie',
              style: Theme.of(context).textTheme.headlineLarge?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
            ),
            const SizedBox(height: 20),
            Text(
              'Monte seu gosto musical',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
            ),
            const SizedBox(height: 10),
            Text(
              'Escolha artistas e gêneros. O Sonorie vai usar só dados locais nesta etapa da migração Flutter.',
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    height: 1.4,
                  ),
            ),
            const SizedBox(height: 24),
            const MigrationBanner(),
            const SizedBox(height: 24),
            SectionLabel('Artistas favoritos'),
            const SizedBox(height: 12),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                for (final artist in artists)
                  ChoicePill(
                    label: artist,
                    selected: selectedArtists.contains(artist),
                    onTap: () => toggle(selectedArtists, artist),
                  ),
              ],
            ),
            const SizedBox(height: 28),
            SectionLabel('Gêneros musicais'),
            const SizedBox(height: 12),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                for (final genre in genres)
                  ChoicePill(
                    label: genre,
                    selected: selectedGenres.contains(genre),
                    onTap: () => toggle(selectedGenres, genre),
                  ),
              ],
            ),
            const SizedBox(height: 28),
            FilledButton(
              onPressed: canEnter ? () => widget.onFinish(selectedArtists, selectedGenres) : null,
              child: const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('Entrar no Sonorie'),
              ),
            ),
            TextButton(
              onPressed: widget.onSkip,
              child: const Text('Pular por enquanto'),
            ),
          ],
        ),
      ),
    );
  }
}

class MigrationBanner extends StatelessWidget {
  const MigrationBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: scheme.primary.withOpacity(0.28),
        borderRadius: BorderRadius.circular(30),
      ),
      child: Row(
        children: [
          Icon(Icons.auto_awesome_rounded, color: scheme.primary, size: 32),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              'Base Flutter verdadeira. Player e biblioteca real entram por módulos, sem função falsa.',
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    fontWeight: FontWeight.w700,
                    height: 1.35,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}

class ChoicePill extends StatelessWidget {
  const ChoicePill({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      borderRadius: BorderRadius.circular(99),
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: selected ? scheme.primary.withOpacity(0.20) : Colors.transparent,
          borderRadius: BorderRadius.circular(99),
          border: Border.all(
            color: selected ? scheme.primary.withOpacity(0.7) : scheme.outlineVariant,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Text(
          label,
          style: Theme.of(context).textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w800,
                color: selected ? scheme.onSurface : scheme.onSurfaceVariant,
              ),
        ),
      ),
    );
  }
}

class TastePill extends StatelessWidget {
  const TastePill({super.key, required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
              fontWeight: FontWeight.w800,
            ),
      ),
    );
  }
}

class SonoriePage extends StatelessWidget {
  const SonoriePage({
    super.key,
    required this.subtitle,
    required this.children,
  });

  final String subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(28, 20, 28, 24),
        children: [
          Center(
            child: Column(
              children: [
                Text(
                  'Sonorie',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.w900,
                      ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontWeight: FontWeight.w700,
                      ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          ...children,
        ],
      ),
    );
  }
}

class SectionLabel extends StatelessWidget {
  const SectionLabel(this.label, {super.key});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(
      label,
      style: Theme.of(context).textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.w900,
          ),
    );
  }
}

class StatCard extends StatelessWidget {
  const StatCard({super.key, required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest.withOpacity(0.36),
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.bodyLarge),
          const SizedBox(height: 10),
          Text(
            value,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w900,
                ),
          ),
        ],
      ),
    );
  }
}

class HonestFeatureCard extends StatelessWidget {
  const HonestFeatureCard({
    super.key,
    required this.icon,
    required this.title,
    required this.body,
  });

  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: scheme.surface.withOpacity(0.54),
        borderRadius: BorderRadius.circular(26),
        border: Border.all(color: scheme.outlineVariant.withOpacity(0.72)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: scheme.primary),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w900,
                      ),
                ),
                const SizedBox(height: 8),
                Text(
                  body,
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        color: scheme.onSurfaceVariant,
                        height: 1.4,
                      ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class ThemeButton extends StatelessWidget {
  const ThemeButton({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      borderRadius: BorderRadius.circular(99),
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
        decoration: BoxDecoration(
          color: selected ? scheme.primary.withOpacity(0.18) : scheme.surfaceContainerHighest.withOpacity(0.58),
          borderRadius: BorderRadius.circular(99),
          border: Border.all(
            color: selected ? scheme.primary.withOpacity(0.56) : Colors.transparent,
          ),
        ),
        child: Text(
          label,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w900,
              ),
        ),
      ),
    );
  }
}

class SettingsCard extends StatelessWidget {
  const SettingsCard({super.key, required this.rows});
  final List<(String, String)> rows;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: scheme.surface.withOpacity(0.54),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: scheme.outlineVariant.withOpacity(0.72)),
      ),
      child: Column(
        children: [
          for (final row in rows)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      row.$1,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                    ),
                  ),
                  Flexible(
                    child: Text(
                      row.$2,
                      textAlign: TextAlign.end,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.w900,
                          ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
