<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, problem, type Readability, type ThemeDesign, type ThemeSpec } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import DangerButton from '#lib/components/DangerButton.svelte';
	import LadderSlider from '#lib/components/LadderSlider.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import Section from '#lib/components/Section.svelte';
	import Swatch from '#lib/components/Swatch.svelte';
	import TextRow from '#lib/components/TextRow.svelte';
	import TextField from '#lib/components/TextField.svelte';
	import ThemePreview from '#lib/components/ThemePreview.svelte';
	import { theme as worn } from '#lib/theme.svelte.ts';
	import { fromHex, isColor, MAX_CHROMA, toHex, type Oklch } from '#lib/oklch.ts';
	import AutoAwesome from '~icons/material-symbols/auto-awesome';
	import Check from '~icons/material-symbols/check';
	import ChevronRight from '~icons/material-symbols/chevron-right';
	import ContentCopy from '~icons/material-symbols/content-copy';
	import FormatSize from '~icons/material-symbols/format-size';
	import RoundedCorner from '~icons/material-symbols/rounded-corner';
	import TableRows from '~icons/material-symbols/table-rows';
	import TextFields from '~icons/material-symbols/text-fields';
	import Title from '~icons/material-symbols/title';
	import { onDestroy } from 'svelte';

	const id = $derived(Number(page.params.id));
	let design = $state<ThemeDesign | null>(null);
	let spec = $state<ThemeSpec | null>(null);
	let error = $state<string | null>(null);
	let gone = $state(false);

	/** The color open in its own pane, by role. */
	let editing = $state<string | null>(null);
	/** The ladders' own position: a hex code alone loses the hue of a grey and the chroma sRGB can't hold. */
	let color = $state<Oklch>({ lightness: 0, chroma: 0, hue: 0 });
	let hex = $state('');

	let saveTimer: ReturnType<typeof setTimeout> | null = null;
	let version = 0;

	async function load() {
		try {
			design = await api.design(id);
			spec = $state.snapshot(design.spec) as ThemeSpec;
			error = null;
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) gone = true;
			else error = problem(e);
		}
	}

	$effect(() => {
		if (id) void load();
	});

	onDestroy(() => {
		// An edit waiting to be saved still goes.
		if (saveTimer && spec) {
			clearTimeout(saveTimer);
			void api.saveTheme(id, $state.snapshot(spec) as ThemeSpec).catch(() => {});
		}
	});

	/** Changes the theme here at once and saves it a moment later, so dragging a ladder writes once. */
	function change(edit: (spec: ThemeSpec) => void) {
		if (!spec) return;
		edit(spec);
		// The theme this browser wears shows each edit at once, as the TV does.
		if (id === worn.id) worn.apply($state.snapshot(spec) as ThemeSpec);
		version++;
		const asked = version;
		if (saveTimer) clearTimeout(saveTimer);
		saveTimer = setTimeout(async () => {
			saveTimer = null;
			try {
				const saved = await api.saveTheme(id, $state.snapshot(spec!) as ThemeSpec);
				// Readability comes back from the TV; the spec here stays, a newer edit may be on its way.
				if (asked === version && design) design = { ...design, readability: saved.readability, roles: saved.roles };
				error = null;
			} catch (e) {
				error = problem(e);
			}
		}, 300);
	}

	function hexOf(role: string): string {
		if (!spec) return '#000000';
		const match = /^guideRuns\[(\d+)]$/.exec(role);
		const value = match ? (spec.colors.guideRuns as string[])[Number(match[1])] : (spec.colors[role] as string);
		return value.toUpperCase();
	}

	function setHex(role: string, value: string) {
		change((s) => {
			const match = /^guideRuns\[(\d+)]$/.exec(role);
			if (match) (s.colors.guideRuns as string[])[Number(match[1])] = value;
			else s.colors[role] = value;
		});
	}

	function open(role: string) {
		editing = role;
		hex = hexOf(role);
		color = fromHex(hex);
		window.scrollTo({ top: 0 });
	}

	function setColor(next: Oklch) {
		color = next;
		hex = toHex(next);
		setHex(editing!, hex);
	}

	function typeHex(value: string) {
		const typed = value.toUpperCase().replace(/[^#0-9A-F]/g, '').slice(0, 7);
		hex = typed;
		const candidate = typed.startsWith('#') ? typed : `#${typed}`;
		if (candidate.length === 7 && isColor(candidate)) {
			color = fromHex(candidate);
			setHex(editing!, candidate);
		}
	}

	async function remove() {
		try {
			await api.deleteTheme(id);
			await goto('/themes');
		} catch (e) {
			error = problem(e);
		}
	}

	async function copy() {
		try {
			await goto(`/themes/${(await api.copyTheme(id)).id}`);
		} catch (e) {
			error = problem(e);
		}
	}

	const problems = $derived((design?.readability ?? []).filter((r) => !r.ok));
	const role = $derived(design?.roles.find((r) => r.key === editing));
	const pairs = $derived((design?.readability ?? []).filter((r) => r.foreground === editing || r.background === editing));
	const scanline = $derived(
		design && spec ? (design.choices.scanlines.find((s) => spec!.effects.scanlines && s.value === spec!.effects.scanlineAlpha) ?? design.choices.scanlines[0]) : null
	);
	const nearestSize = $derived(
		design && spec
			? design.choices.textSizes.reduce((best, s) => (Math.abs(s.value - spec!.typography.scale) < Math.abs(best.value - spec!.typography.scale) ? s : best))
			: null
	);
	const trail = [
		{ label: 'Home', href: '/' },
		{ label: 'Themes', href: '/themes' }
	];

	function pairText(pair: Readability) {
		return pair.foreground === editing ? pair.inForeground : pair.inBackground;
	}
</script>

{#if gone}
	<Page title="Theme" {trail}><Notice text="This theme is gone." /></Page>
{:else if design && spec && design.isBuiltIn}
	<Page title={spec.name} {trail} note="Copy a built-in theme to edit it.">
		{#snippet actions()}<Button label="Copy theme" icon={ContentCopy} primary onclick={copy} />{/snippet}
		{#snippet aside()}<ThemePreview spec={spec!} />{/snippet}
		{#if error}<Notice text={error} error />{/if}
	</Page>
{:else if design && spec && editing && role}
	<Page title={role.label} trail={[...trail, { label: spec.name, href: `/themes/${id}` }]} note={role.about}>
		{#snippet actions()}<Button label="Done" icon={Check} primary onclick={() => (editing = null)} />{/snippet}
		{#snippet aside()}
			<div class="mb-4 h-14 rounded-control border border-line" style:background={hexOf(editing!)}></div>
			<ThemePreview spec={spec!} />
		{/snippet}
		{#if error}<Notice text={error} error />{/if}
		<LadderSlider
			label="Brightness"
			text="{Math.round(color.lightness * 100)}%"
			value={color.lightness}
			min={0}
			max={1}
			step={0.01}
			onchange={(lightness) => setColor({ ...color, lightness })}
		/>
		<LadderSlider
			label="Saturation"
			text="{Math.round((color.chroma / MAX_CHROMA) * 100)}%"
			value={color.chroma}
			min={0}
			max={MAX_CHROMA}
			step={0.005}
			onchange={(chroma) => setColor({ ...color, chroma })}
		/>
		<LadderSlider label="Hue" text="{Math.round(color.hue)}°" value={color.hue} min={0} max={359} step={5} onchange={(hue) => setColor({ ...color, hue })} />
		<div class="px-4 py-2">
			<TextField label="Hex code" value={hex} maxlength={7} tabular spellcheck="false" oninput={(e) => typeHex(e.currentTarget.value)} />
		</div>
		{#if pairs.length > 0}<Section title="Readability" />{/if}
		{#each pairs as pair (pair.foreground + pair.background)}
			<Notice text={pairText(pair)} error={!pair.ok} />
		{/each}
	</Page>
{:else if design && spec}
	<Page title={spec.name} {trail} note="Edits save as you go.">
		{#snippet aside()}<ThemePreview spec={spec!} />{/snippet}
		{#if error}<Notice text={error} error />{/if}
		{#each problems as pair (pair.foreground + pair.background)}
			<Notice text={pair.text} error />
		{/each}
		<TextRow
			title="Name"
			value={spec.name}
			icon={Title}
			maxlength={32}
			onsave={(name) => {
				if (!name) return 'Give the theme a name.';
				change((s) => (s.name = name));
				return null;
			}}
		/>
		<Section title="Colors" />
		{#each design.roles as r (r.key)}
			<Row title={r.label} summary={r.about} onclick={() => open(r.key)}>
				{#snippet leading()}<Swatch color={hexOf(r.key)} />{/snippet}
				{#snippet end()}<span class="tabular text-label-l font-semibold">{hexOf(r.key)}</span><ChevronRight class="size-6" />{/snippet}
			</Row>
		{/each}
		<Section title="Type" />
		<ChoiceRow
			title="Headings"
			value={spec.typography.display}
			choices={design.choices.fonts.map((f) => f.value)}
			label={(v) => design!.choices.fonts.find((f) => f.value === v)?.label ?? v}
			about="Page titles, channel numbers, times and field labels."
			onselect={(v) => change((s) => (s.typography.display = v))}
			icon={Title}
		/>
		<ChoiceRow
			title="Body text"
			value={spec.typography.body}
			choices={design.choices.fonts.map((f) => f.value)}
			label={(v) => design!.choices.fonts.find((f) => f.value === v)?.label ?? v}
			onselect={(v) => change((s) => (s.typography.body = v))}
			icon={TextFields}
		/>
		{#if nearestSize}
			<ChoiceRow
				title="Text size"
				value={nearestSize.value}
				choices={design.choices.textSizes.map((t) => t.value)}
				label={(v) => design!.choices.textSizes.find((t) => t.value === v)?.label ?? String(v)}
				onselect={(v) => change((s) => (s.typography.scale = v))}
				icon={FormatSize}
			/>
		{/if}
		<Section title="Guide" />
		<ChoiceRow
			title="Rows"
			value={spec.guide.density}
			choices={design.choices.densities.map((d) => d.value)}
			label={(v) => design!.choices.densities.find((d) => d.value === v)?.label ?? v}
			about="How tall the guide's channel rows are."
			onselect={(v) => change((s) => (s.guide.density = v))}
			icon={TableRows}
		/>
		<ChoiceRow
			title="Cell corners"
			value={spec.guide.cellRadiusDp}
			choices={design.choices.radii.map((r) => r.value)}
			label={(v) => `${v} dp`}
			onselect={(v) => change((s) => (s.guide.cellRadiusDp = v))}
			icon={RoundedCorner}
		/>
		<Section title="Effects" />
		{#if scanline}
			<ChoiceRow
				title="Scanlines"
				value={scanline.value}
				choices={design.choices.scanlines.map((s) => s.value)}
				label={(v) => design!.choices.scanlines.find((s) => s.value === v)?.label ?? String(v)}
				about="A picture tube's lines over the whole screen."
				onselect={(alpha) =>
					change((s) => {
						s.effects.scanlines = alpha > 0;
						if (alpha > 0) s.effects.scanlineAlpha = alpha;
					})}
				icon={AutoAwesome}
			/>
		{/if}
		<Buttons foot>
			<DangerButton label="Delete theme" question="Delete {spec.name}?" explanation="Profiles using it go back to the default." confirm="Delete" onconfirm={remove} />
		</Buttons>
	</Page>
{:else if error}
	<Page title="Theme" {trail}><Notice text={error} error /></Page>
{/if}
