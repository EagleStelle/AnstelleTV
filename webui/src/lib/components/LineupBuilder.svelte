<script lang="ts">
	import { api, problem, type Builder, type Field, type Lineup, type Op, type Option, type Pick, type Reach } from '#lib/api.ts';
	import { number } from '#lib/format.ts';
	import Add from '~icons/material-symbols/add';
	import Block from '~icons/material-symbols/block';
	import Check from '~icons/material-symbols/check';
	import Delete from '~icons/material-symbols/delete';
	import FilterAlt from '~icons/material-symbols/filter-alt';
	import LibraryAdd from '~icons/material-symbols/library-add';
	import Scale from '~icons/material-symbols/scale';
	import { onMount, untrack, type Snippet } from 'svelte';
	import Button from './Button.svelte';
	import Buttons from './Buttons.svelte';
	import ChoiceRow from './ChoiceRow.svelte';
	import Dialog from './Dialog.svelte';
	import Notice from './Notice.svelte';
	import ReachReadout from './ReachReadout.svelte';
	import RemoveKey from './RemoveKey.svelte';
	import Row from './Row.svelte';
	import Search from './Search.svelte';
	import TextField from './TextField.svelte';
	import WithKey from './WithKey.svelte';
	import Section from './Section.svelte';

	/**
	 * A channel's lineup: rule sets (any set may match, every rule in a set must), then series and
	 * movies always or never included. The TV describes each rule in the app's words and counts
	 * what the lineup airs after every change.
	 */
	interface Props {
		lineup: Lineup;
		saveLabel: string;
		onsave: (lineup: Lineup) => Promise<string | null>;
		/** Fields above the rules (a new channel's name and group). */
		head?: Snippet;
		/** Where the reach readout goes; the page puts it beside the list. */
		reach?: Reach | null;
	}

	let { lineup = $bindable(), saveLabel, onsave, head, reach = $bindable(null) }: Props = $props();

	let builder = $state<Builder | null>(null);
	let error = $state<string | null>(null);
	let busy = $state(false);
	let previewTimer: ReturnType<typeof setTimeout> | null = null;

	// Adding a rule: field, then comparison, then values.
	let adding = $state(false);
	let group = $state(0);
	let field = $state<Field | null>(null);
	let op = $state<Op | null>(null);
	let query = $state('');
	let options = $state<Option[]>([]);
	let selected = $state<string[]>([]);
	let from = $state<Option | null>(null);
	let typed = $state('');

	// Adding a series or movie to always or never include.
	let titles = $state<{ include: boolean; movies: boolean } | null>(null);
	let titlesOpen = $state(false);

	onMount(async () => {
		try {
			builder = await api.builder();
		} catch (e) {
			error = problem(e);
		}
		await preview();
	});

	/** Each change counts up, so an answer to an older lineup never replaces a newer one. */
	let version = 0;

	/** Describes the lineup and counts what it airs, a moment after the last change. */
	function changed() {
		version++;
		if (previewTimer) clearTimeout(previewTimer);
		previewTimer = setTimeout(() => void preview(), 200);
	}

	async function preview() {
		const asked = version;
		try {
			const result = await api.preview(lineup);
			if (asked !== version) return;
			lineup = result.lineup;
			reach = result.reach;
			error = null;
		} catch (e) {
			if (asked === version) error = problem(e);
		}
	}

	function addRule(g: number) {
		group = g;
		field = null;
		op = null;
		adding = true;
	}

	function chooseField(f: Field) {
		field = f;
	}

	async function chooseOp(o: Op) {
		op = o;
		query = '';
		selected = [];
		from = null;
		typed = '';
		if (!o.typed) await loadOptions();
	}

	async function loadOptions() {
		if (!field || !op) return;
		try {
			options = await api.options(field.field, op.op, query, from?.value);
		} catch (e) {
			error = problem(e);
		}
	}

	function add(values: string[]) {
		if (!field || !op) return;
		const rule = { field: field.field, op: op.op, values };
		const groups = lineup.groups.map((g) => ({ ...g, rules: [...g.rules] }));
		if (group >= groups.length) groups.push({ rules: [rule], weight: 1 });
		else groups[group].rules.push(rule);
		lineup = { ...lineup, groups };
		adding = false;
		changed();
	}

	async function pickOption(option: Option) {
		if (!op) return;
		if (op.op === 'BETWEEN' && !from) {
			from = option;
			query = '';
			await loadOptions();
		} else if (op.op === 'BETWEEN' && from) {
			add([from.value, option.value]);
		} else if (op.single) {
			add([option.value]);
		} else {
			selected = selected.includes(option.value) ? selected.filter((v) => v !== option.value) : [...selected, option.value];
		}
	}

	function confirmTyped(event: SubmitEvent) {
		event.preventDefault();
		if (!field) return;
		const values = (field.field === 'KEYWORD' ? typed.split(',') : [typed]).map((v) => v.trim()).filter(Boolean);
		if (values.length === 0) return;
		add(values);
	}

	function back() {
		if (from) {
			from = null;
			void loadOptions();
		} else if (op) op = null;
		else if (field) field = null;
		else adding = false;
	}

	function removeRule(g: number, r: number) {
		const groups = lineup.groups.map((set, i) => (i === g ? { ...set, rules: set.rules.filter((_, j) => j !== r) } : set)).filter((set) => set.rules.length > 0);
		lineup = { ...lineup, groups };
		changed();
	}

	function removeGroup(g: number) {
		lineup = { ...lineup, groups: lineup.groups.filter((_, i) => i !== g) };
		changed();
	}

	function setGroupWeight(g: number, weight: number) {
		lineup = { ...lineup, groups: lineup.groups.map((set, i) => (i === g ? { ...set, weight } : set)) };
		changed();
	}

	async function openTitles(include: boolean, movies: boolean) {
		titles = { include, movies };
		query = '';
		titlesOpen = true;
		await loadTitles();
	}

	async function loadTitles() {
		if (!titles) return;
		try {
			options = await api.options(titles.movies ? 'ITEM' : 'SERIES', 'ANY_OF', query);
		} catch (e) {
			error = problem(e);
		}
	}

	function pickTitle(option: Option) {
		if (!titles) return;
		const pick: Pick = { kind: titles.movies ? 'movie' : 'series', remoteId: option.value, weight: 1, title: option.label };
		if (titles.include) lineup = { ...lineup, includes: [...lineup.includes.filter((p) => p.remoteId !== pick.remoteId), pick] };
		else lineup = { ...lineup, excludes: [...lineup.excludes.filter((p) => p.remoteId !== pick.remoteId), pick] };
		titlesOpen = false;
		changed();
	}

	function removePick(include: boolean, index: number) {
		if (include) lineup = { ...lineup, includes: lineup.includes.filter((_, i) => i !== index) };
		else lineup = { ...lineup, excludes: lineup.excludes.filter((_, i) => i !== index) };
		changed();
	}

	function setPickWeight(index: number, weight: number) {
		lineup = { ...lineup, includes: lineup.includes.map((p, i) => (i === index ? { ...p, weight } : p)) };
		changed();
	}

	async function save() {
		busy = true;
		error = await onsave(lineup);
		busy = false;
	}

	const weights = $derived(builder?.weights ?? []);
	const weightOf = (w: number) => weights.find((s) => s.weight === w);

	// The lists follow the search as it is typed.
	let searchTimer: ReturnType<typeof setTimeout> | null = null;
	$effect(() => {
		void query;
		untrack(() => {
			const load = titlesOpen ? loadTitles : adding && op && !op.typed ? loadOptions : null;
			if (!load) return;
			if (searchTimer) clearTimeout(searchTimer);
			searchTimer = setTimeout(() => void load(), 150);
		});
	});

	const stepTitle = $derived(
		!field ? 'Add rule' : !op ? field.label : from ? `${field.label} ${op.label} ${from.label} and…` : `${field.label} ${op.label}…`
	);
	const stepAbout = $derived(
		!field
			? 'What should the rule look at?'
			: !op
				? 'How should it compare?'
				: op.typed
					? field.field === 'KEYWORD'
						? 'Words or phrases, separated by commas. Searches titles and tags.'
						: 'Matches anything that contains this text.'
					: op.op === 'BETWEEN'
						? from
							? 'Pick where the range ends.'
							: 'Pick where the range starts.'
						: op.single
							? 'Pick one.'
							: 'Pick one or more, then add the rule.'
	);
</script>

{@render head?.()}

{#each lineup.groups as set, g (g)}
	<Section title={g === 0 ? 'Rule set 1' : `Or rule set ${g + 1}`} />
	{#each set.rules as rule, r (r)}
		<WithKey>
			<Row title={rule.label ?? `${rule.field} ${rule.values.join(', ')}`} icon={FilterAlt} />
			{#snippet key()}<RemoveKey label="Remove rule" onclick={() => removeRule(g, r)} />{/snippet}
		</WithKey>
	{/each}
	<ChoiceRow
		title="Weight"
		value={set.weight}
		choices={weights.map((w) => w.weight)}
		label={(w) => weightOf(w)?.label ?? `×${w}`}
		hint={(w) => weightOf(w)?.hint}
		about="How often this set's series and movies play."
		onselect={(w) => setGroupWeight(g, w)}
		icon={Scale}
	/>
	<Buttons>
		<Button label="Add rule" icon={Add} onclick={() => addRule(g)} />
		<Button label="Remove rule set" icon={Delete} danger onclick={() => removeGroup(g)} />
	</Buttons>
{/each}
<Buttons><Button label={lineup.groups.length === 0 ? 'Add rule' : 'Add rule set'} icon={Add} onclick={() => addRule(lineup.groups.length)} /></Buttons>

{#each [true, false] as include (include)}
	{@const picks = include ? lineup.includes : lineup.excludes}
	<Section title={include ? 'Always include' : 'Never include'} />
	{#each picks as pick, i (pick.remoteId)}
		<WithKey>
			{#if include}
				<ChoiceRow
					title={pick.title ?? pick.remoteId}
					value={pick.weight}
					summary="Weight {weightOf(pick.weight)?.label ?? `×${pick.weight}`}"
					choices={weights.map((w) => w.weight)}
					label={(w) => `Weight ${weightOf(w)?.label ?? `×${w}`}`}
					hint={(w) => weightOf(w)?.hint}
					onselect={(w) => setPickWeight(i, w)}
					icon={LibraryAdd}
				/>
			{:else}
				<Row title={pick.title ?? pick.remoteId} icon={Block} />
			{/if}
			{#snippet key()}<RemoveKey label="Remove {pick.title ?? ''}" onclick={() => removePick(include, i)} />{/snippet}
		</WithKey>
	{/each}
	<Buttons>
		<Button label="Add series" icon={Add} onclick={() => openTitles(include, false)} />
		<Button label="Add movie" icon={Add} onclick={() => openTitles(include, true)} />
	</Buttons>
{/each}

{#if error}<Notice text={error} error />{/if}
<div class="mt-4">
	<Buttons><Button label={busy ? 'Saving…' : saveLabel} icon={Check} primary disabled={busy} onclick={save} /></Buttons>
</div>

<Dialog bind:open={adding} title={stepTitle} about={stepAbout} wide>
	<div class="-mx-3 flex flex-col gap-0.5">
		{#if !field}
			{#each builder?.fields ?? [] as f (f.field)}
				<Row title={f.label} summary={f.hint} onclick={() => chooseField(f)} />
			{/each}
		{:else if !op}
			{#each field.ops as o (o.op)}
				<Row title="{field.label} {o.label}…" onclick={() => chooseOp(o)} />
			{/each}
		{:else if op.typed}
			<form class="flex flex-col gap-3 px-3" onsubmit={confirmTyped}>
				<!-- svelte-ignore a11y_autofocus -->
				<TextField label={field.field === 'KEYWORD' ? 'Keywords' : 'Text'} bind:value={typed} autofocus />
				<Button label="Add rule" icon={Check} type="submit" primary disabled={!typed.trim()} />
			</form>
		{:else}
			<Search bind:value={query} label="Search" />
			<div class="-mx-3 flex max-h-[50vh] flex-col gap-0.5 overflow-y-auto">
				{#each options as option (option.value)}
					{@const picked = selected.includes(option.value) || from?.value === option.value}
					<Row
						title={option.label}
						summary={field.field === 'ITEM' ? undefined : `${number(option.count)} ${option.count === 1 ? 'item' : 'items'}`}
						onclick={() => pickOption(option)}
						aria-pressed={op.single ? undefined : picked}
					>
						{#snippet end()}{#if picked}<Check class="size-6" />{/if}{/snippet}
					</Row>
				{/each}
				{#if options.length === 0}<Notice text="Nothing in your library has one." />{/if}
			</div>
			{#if !op.single}
				<Button label="Add rule" icon={Check} primary disabled={selected.length === 0} onclick={() => add(selected)} />
			{/if}
		{/if}
	</div>
	{#snippet actions()}
		<Button label={field ? 'Back' : 'Cancel'} onclick={back} />
	{/snippet}
</Dialog>

<Dialog
	bind:open={titlesOpen}
	title={titles?.include ? 'Always include' : 'Never include'}
	about={titles?.include ? 'Always plays on this channel, whatever the rules say.' : 'Never plays on this channel.'}
	wide
>
	<Search bind:value={query} label="Search" />
	<div class="-mx-3 flex max-h-[50vh] flex-col gap-0.5 overflow-y-auto">
		{#each options as option (option.value)}
			<Row
				title={option.label}
				summary={titles?.movies ? undefined : `${number(option.count)} ${option.count === 1 ? 'episode' : 'episodes'}`}
				onclick={() => pickTitle(option)}
			/>
		{/each}
		{#if options.length === 0}<Notice text={titles?.movies ? 'No matching movies.' : 'No matching series.'} />{/if}
	</div>
	{#snippet actions()}
		<Button label="Cancel" onclick={() => (titlesOpen = false)} />
	{/snippet}
</Dialog>
