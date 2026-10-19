<script lang="ts">
	import { beforeNavigate, goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, problem, type Readability, type ThemeDesign, type ThemeSpec } from '#lib/api.ts';
	import type { Component, Snippet } from 'svelte';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import DangerButton from '#lib/components/DangerButton.svelte';
	import LadderSlider from '#lib/components/LadderSlider.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import RadioMark from '#lib/components/RadioMark.svelte';
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
	import Delete from '~icons/material-symbols/delete';
	import FormatSize from '~icons/material-symbols/format-size';
	import Save from '~icons/material-symbols/save';
	import TableRows from '~icons/material-symbols/table-rows';
	import TextFields from '~icons/material-symbols/text-fields';
	import Title from '~icons/material-symbols/title';
	import ViewWeek from '~icons/material-symbols/view-week';

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

	/** The theme as last saved, as JSON; the spec differs from it while there are edits to save. */
	let saved = $state('');
	/** A pane's own copy of the theme while a color or choice is open; only Done hands it to the spec. */
	let working = $state<ThemeSpec | null>(null);
	/** The readout from before the pane opened, back in place when its edits are discarded. */
	let openedDesign: ThemeDesign | null = null;
	let checkTimer: ReturnType<typeof setTimeout> | null = null;
	let version = 0;
	/** Where the person was going when asked to discard their edits. */
	let leavingTo: URL | null = null;
	let leaveOpen = $state(false);
	let discarding = false;

	async function load() {
		try {
			design = await api.design(id);
			spec = $state.snapshot(design.spec) as ThemeSpec;
			saved = JSON.stringify(spec);
			error = null;
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) gone = true;
			else error = problem(e);
		}
	}

	$effect(() => {
		if (id) void load();
	});

	/** What the page shows: the pane's copy while one is open, else the theme. */
	const shown = $derived(working ?? spec);
	const unsaved = $derived(shown !== null && JSON.stringify(shown) !== saved);
	const paneEdited = $derived(working !== null && JSON.stringify(working) !== JSON.stringify(spec));

	// Leaving with edits unsaved asks first; closing the tab gets the browser's own question.
	beforeNavigate((navigation) => {
		if (!unsaved || discarding) return;
		navigation.cancel();
		if (navigation.type !== 'leave' && navigation.to) {
			leavingTo = navigation.to.url;
			leaveOpen = true;
		}
	});

	function discard() {
		const to = leavingTo;
		leaveOpen = false;
		discarding = true;
		if (to) void goto(to);
	}

	/** Changes the open pane's copy, else the theme; nothing is kept until Save. The TV reads it a moment later, so readability follows each edit. */
	function change(edit: (spec: ThemeSpec) => void) {
		const target = working ?? spec;
		if (!target) return;
		edit(target);
		version++;
		const asked = version;
		if (checkTimer) clearTimeout(checkTimer);
		checkTimer = setTimeout(async () => {
			checkTimer = null;
			try {
				const checked = await api.checkTheme(id, $state.snapshot(working ?? spec!) as ThemeSpec);
				// A newer edit may be on its way; only the latest one's readout counts.
				if (asked === version && design) design = { ...design, readability: checked.readability, roles: checked.roles };
				error = null;
			} catch (e) {
				error = problem(e);
			}
		}, 300);
	}

	async function save() {
		if (!spec) return;
		const edited = $state.snapshot(spec) as ThemeSpec;
		try {
			const result = await api.saveTheme(id, edited);
			saved = JSON.stringify(edited);
			if (design) design = { ...design, readability: result.readability, roles: result.roles };
			// The theme this browser wears shows the saved edits at once, as the TV does.
			if (id === worn.id) worn.apply(edited);
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	function hexOf(role: string): string {
		if (!shown) return '#000000';
		const match = /^guideRuns\[(\d+)]$/.exec(role);
		const value = match ? (shown.colors.guideRuns as string[])[Number(match[1])] : (shown.colors[role] as string);
		return value.toUpperCase();
	}

	function setHex(role: string, value: string) {
		change((s) => {
			const match = /^guideRuns\[(\d+)]$/.exec(role);
			if (match) (s.colors.guideRuns as string[])[Number(match[1])] = value;
			else s.colors[role] = value;
		});
	}

	/** Opens a color or a choice on its own page, editing a copy of the theme. */
	function open(key: string) {
		openedDesign = design;
		working = $state.snapshot(spec) as ThemeSpec;
		editing = key;
		if (design?.roles.some((r) => r.key === key)) {
			hex = hexOf(key);
			color = fromHex(hex);
		}
		window.scrollTo({ top: 0 });
	}

	/** Picks value [index] of a Type or Screen setting; the TV applies it, so its rules live in one place. */
	async function choose(key: string, index: number) {
		if (!working) return;
		version++;
		const asked = version;
		try {
			const picked = await api.checkTheme(id, $state.snapshot(working) as ThemeSpec, { choice: key, index });
			if (asked === version && working) {
				working = picked.spec;
				design = picked;
			}
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	/** Done: the pane's copy becomes the theme. */
	function keep() {
		if (working) spec = $state.snapshot(working) as ThemeSpec;
		working = null;
		editing = null;
	}

	/** Discard: the pane's copy goes, and the readout from before it. */
	function drop() {
		version++;
		working = null;
		editing = null;
		if (openedDesign) design = openedDesign;
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
			discarding = true;
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
	const choice = $derived(design?.choices.find((c) => c.key === editing));
	const sections = $derived([...new Set((design?.choices ?? []).map((c) => c.section))]);
	/** Each Type and Screen setting's icon, by key. */
	const choiceIcons: Record<string, Component<any>> = {
		display: Title,
		body: TextFields,
		scale: FormatSize,
		density: TableRows,
		progress: ViewWeek,
		scanlines: AutoAwesome
	};
	const trail = [
		{ label: 'Home', href: '/' },
		{ label: 'Themes', href: '/themes' }
	];

	function pairText(pair: Readability) {
		return pair.foreground === editing ? pair.inForeground : pair.inBackground;
	}
</script>

<!-- A color or a choice opened from the menu: its own page beside the preview; Done keeps its edits, Discard drops them. -->
{#snippet pane(title: string, body: Snippet)}
	<Page {title} trail={[...trail, { label: spec!.name, href: `/themes/${id}` }]}>
		{#snippet actions()}
			<Button label="Done" icon={Check} primary onclick={keep} />
			<Button label="Discard" icon={Delete} danger disabled={!paneEdited} onclick={drop} />
		{/snippet}
		{#snippet aside()}<ThemePreview spec={shown!} />{/snippet}
		{#if error}<Notice text={error} error />{/if}
		{@render body()}
	</Page>
{/snippet}

{#snippet colorBody()}
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
{/snippet}

{#snippet choiceBody()}
	<div role="radiogroup" aria-label={choice!.title}>
		{#each choice!.labels as label, i (i)}
			<Row title={label} onclick={() => choose(choice!.key, i)} role="radio" aria-checked={i === choice!.selected}>
				{#snippet leading()}<RadioMark checked={i === choice!.selected} />{/snippet}
			</Row>
		{/each}
	</div>
{/snippet}

{#if gone}
	<Page title="Theme" {trail}><Notice text="This theme no longer exists." /></Page>
{:else if design && spec && design.isBuiltIn}
	<Page title={spec.name} {trail}>
		{#snippet actions()}<Button label="Copy and edit" icon={ContentCopy} primary onclick={copy} />{/snippet}
		{#snippet aside()}<ThemePreview spec={spec!} />{/snippet}
		{#if error}<Notice text={error} error />{/if}
	</Page>
{:else if design && spec && editing && role}
	{@render pane(role.label, colorBody)}
{:else if design && spec && editing && choice}
	{@render pane(choice.title, choiceBody)}
{:else if design && spec}
	<Page title={spec.name} {trail}>
		{#snippet actions()}<Button label="Save" icon={Save} primary disabled={!unsaved} onclick={save} />{/snippet}
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
		{#each sections as heading (heading)}
			<Section title={heading} />
			{#each design.choices.filter((c) => c.section === heading) as c (c.key)}
				<Row title={c.title} summary={c.labels[c.selected]} icon={choiceIcons[c.key]} onclick={() => open(c.key)}>
					{#snippet end()}<ChevronRight class="size-6" />{/snippet}
				</Row>
			{/each}
		{/each}
		<Buttons foot>
			<DangerButton label="Delete theme" question="Delete {spec.name}?" explanation="Profiles using it switch back to Rose." confirm="Delete" onconfirm={remove} />
		</Buttons>
	</Page>
{:else if error}
	<Page title="Theme" {trail}><Notice text={error} error /></Page>
{/if}

<Dialog bind:open={leaveOpen} title="Discard changes?" about="Edits since you last saved will be lost.">
	{#snippet actions()}
		<Button label="Cancel" autofocus onclick={() => (leaveOpen = false)} />
		<Button label="Discard" icon={Delete} danger onclick={discard} />
	{/snippet}
</Dialog>
