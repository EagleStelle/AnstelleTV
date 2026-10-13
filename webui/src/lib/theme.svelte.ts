import { api, type ThemeSpec } from './api.ts';

// The browser wears the theme its paired profile wears on the TV: every color role, the fonts,
// the text size and the scanlines, as the CSS variables app.css reads. The last one worn is
// remembered here so a reload doesn't flash the default first.

const STORAGE_KEY = 'anstelle.theme';

const FONTS: Record<string, string> = {
	CONDENSED: "'Barlow Condensed', 'Barlow', system-ui, sans-serif",
	SANS: "'Barlow', system-ui, sans-serif",
	SERIF: 'Georgia, "Times New Roman", serif',
	MONO: 'ui-monospace, "Cascadia Mono", Menlo, Consolas, monospace'
};

/** The roles the web UI draws with, as the theme names them. */
const ROLES = [
	'background',
	'surface',
	'surfaceHigh',
	'line',
	'text',
	'textMuted',
	'accent',
	'onAccent',
	'accentContainer',
	'onAccentContainer'
] as const;

const kebab = (role: string) => role.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);

/** WCAG relative luminance, to tell a light ground from a dark one. */
function luminance(hex: string): number {
	const rgb = parseInt(hex.replace('#', '').slice(-6), 16);
	const channel = (shift: number) => {
		const c = ((rgb >> shift) & 0xff) / 255;
		return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
	};
	return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0);
}

class ThemeStore {
	/** The worn theme's id, or null before it is known. */
	id = $state<number | null>(null);
	spec = $state<ThemeSpec | null>(null);

	constructor() {
		try {
			const saved = localStorage.getItem(STORAGE_KEY);
			if (saved) this.apply(JSON.parse(saved) as ThemeSpec);
		} catch {
			// No storage here: the default shows until the theme arrives.
		}
	}

	/** Reads the paired profile's theme again and wears it. */
	async refresh(): Promise<void> {
		try {
			const worn = await api.worn();
			this.id = worn.id;
			this.apply(worn.spec);
		} catch {
			// Not paired, or the TV is away: keep what is on.
		}
	}

	/** Puts [themeId] on the paired profile, here and on the TV. */
	async use(themeId: number): Promise<void> {
		await api.useTheme(themeId);
		await this.refresh();
	}

	/** Wears [spec]: on the TV this is the profile's theme; the designer shows edits to it live. */
	apply(spec: ThemeSpec): void {
		this.spec = spec;
		const style = document.documentElement.style;
		const colors = spec.colors as Record<string, string>;
		for (const role of ROLES) {
			if (colors[role]) style.setProperty(`--theme-${kebab(role)}`, colors[role]);
		}
		style.setProperty('--theme-font-display', FONTS[spec.typography.display] ?? FONTS.CONDENSED);
		style.setProperty('--theme-font-body', FONTS[spec.typography.body] ?? FONTS.SANS);
		style.setProperty('--theme-scale', String(spec.typography.scale || 1));
		style.setProperty('--theme-scanlines', String(spec.effects.scanlines ? spec.effects.scanlineAlpha : 0));
		const light = luminance(colors.background) > 0.5;
		style.colorScheme = light ? 'light' : 'dark';
		document.querySelector('meta[name="theme-color"]')?.setAttribute('content', colors.background);
		try {
			localStorage.setItem(STORAGE_KEY, JSON.stringify(spec));
		} catch {
			// Remembering it is only a convenience.
		}
	}

	/** Back to the default, for a browser no longer paired. */
	reset(): void {
		this.id = null;
		this.spec = null;
		document.documentElement.removeAttribute('style');
		try {
			localStorage.removeItem(STORAGE_KEY);
		} catch {
			// Nothing to forget.
		}
	}
}

export const theme = new ThemeStore();
