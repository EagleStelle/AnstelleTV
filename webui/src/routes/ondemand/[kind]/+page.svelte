<script lang="ts">
	import { page } from '$app/state';
	import { api, problem, type CatalogKind, type Shelf } from '#lib/api.ts';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { shelfIcon } from '#lib/ondemand.ts';
	import DragHandle from '~icons/material-symbols/drag-handle';
	import { onMount } from 'svelte';

	/**
	 * One kind's rows in the order its tab shows them, each moved by its handle: its fixed rows
	 * (Continue watching, Next up, Recently added) and every library channel that airs that kind.
	 */
	const key = $derived(page.params.kind);
	let kind = $state<CatalogKind | null>(null);
	let gone = $state(false);
	let error = $state<string | null>(null);
	let dragging = $state<string | null>(null);

	async function load() {
		try {
			const found = (await api.onDemand()).kinds.find((k) => k.key === key);
			gone = !found;
			kind = found ?? null;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	$effect(() => {
		if (key) void load();
	});

	onMount(() => live.subscribe(['setting', 'channel', 'channel_rule', 'channel_daypart', 'media_item'], () => void load()));

	/** Moves [shelf] by [delta] places, in the list at once and on the TV. */
	async function move(shelf: Shelf, delta: number) {
		if (!kind) return;
		const rows = kind.rows;
		const from = rows.indexOf(shelf);
		const to = Math.min(Math.max(from + delta, 0), rows.length - 1);
		if (from === to) return;
		const reordered = [...rows];
		reordered.splice(to, 0, ...reordered.splice(from, 1));
		kind = { ...kind, rows: reordered };
		try {
			await api.setRows(kind.key, reordered.map((r) => r.key));
		} catch (e) {
			error = problem(e);
			await load();
		}
	}

	function keys(event: KeyboardEvent, shelf: Shelf) {
		if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
			event.preventDefault();
			void move(shelf, event.key === 'ArrowUp' ? -1 : 1).then(() => {
				// Focus stays on the handle of the row carried.
				document.getElementById(`handle-${shelf.key}`)?.focus();
			});
		}
	}

	function drop(target: Shelf) {
		if (!kind || dragging === null) return;
		const carried = kind.rows.find((r) => r.key === dragging);
		dragging = null;
		if (carried) void move(carried, kind.rows.indexOf(target) - kind.rows.indexOf(carried));
	}

	const trail = [
		{ label: 'Home', href: '/' },
		{ label: 'On Demand', href: '/ondemand' }
	];
</script>

{#if gone}
	<Page title="Rows" {trail}><Notice text="On Demand has no such tab." /></Page>
{:else if kind}
	<Page
		title={kind.label}
		{trail}
		note="Drag a row by its handle to move it."
	>
		{#if error}<Notice text={error} error />{/if}
		{#if kind.rows.length === 0}<Notice text="No rows yet." />{/if}
		<ol class="m-0 flex list-none flex-col gap-0.5 p-0">
			{#each kind.rows as shelf (shelf.key)}
				<!-- A row in hand steps up to the raised surface, never a shadow. -->
				<li
					class={['flex items-center rounded-control transition-colors', dragging === shelf.key && 'bg-surface-high']}
					ondragover={(event) => dragging !== null && event.preventDefault()}
					ondrop={() => drop(shelf)}
				>
					<div class="min-w-0 flex-1">
						{#if shelf.channelNumber !== undefined}
							<Row title={shelf.label}>
								{#snippet leading()}<span class="tabular min-w-10 text-title-m font-semibold text-text">{shelf.channelNumber}</span>{/snippet}
							</Row>
						{:else}
							<Row title={shelf.label} icon={shelfIcon(shelf.type)} />
						{/if}
					</div>
					<button
						id="handle-{shelf.key}"
						class="grid size-14 shrink-0 cursor-grab place-items-center rounded-control text-text-muted transition-colors hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent"
						draggable="true"
						aria-label="Move {shelf.label}. Up and down arrows move it."
						ondragstart={(event) => {
							dragging = shelf.key;
							event.dataTransfer?.setData('text/plain', shelf.key);
						}}
						ondragend={() => (dragging = null)}
						onkeydown={(event) => keys(event, shelf)}
					>
						<DragHandle class="size-6" />
					</button>
				</li>
			{/each}
		</ol>
	</Page>
{:else if error}
	<Page title="Rows" {trail}><Notice text={error} error /></Page>
{/if}
