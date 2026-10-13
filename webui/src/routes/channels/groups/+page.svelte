<script lang="ts">
	import { api, problem, type Group } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import TextField from '#lib/components/TextField.svelte';
	import { counted } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import CreateNewFolder from '~icons/material-symbols/create-new-folder';
	import DragHandle from '~icons/material-symbols/drag-handle';
	import Edit from '~icons/material-symbols/edit';
	import Folder from '~icons/material-symbols/folder';
	import FolderSpecial from '~icons/material-symbols/folder-special';
	import { onMount } from 'svelte';

	let groups = $state<Group[] | null>(null);
	let nextStart = $state(0);
	let error = $state<string | null>(null);

	// The group being edited (null: a new one), and its fields.
	let editing = $state<Group | null>(null);
	let open = $state(false);
	let name = $state('');
	let start = $state('');
	let problemText = $state<string | null>(null);

	let dragging = $state<number | null>(null);

	async function load() {
		try {
			const result = await api.groups();
			groups = result.groups;
			nextStart = result.nextStart;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['channel_group', 'channel'], () => void load());
	});

	function edit(group: Group | null) {
		editing = group;
		name = group?.name ?? '';
		start = String(group?.start ?? nextStart);
		problemText = null;
		open = true;
	}

	async function save(event: SubmitEvent) {
		event.preventDefault();
		const startNumber = Number(start);
		if (!/^\d{1,4}$/.test(start)) {
			problemText = 'A start is a number from 0 to 9999.';
			return;
		}
		try {
			if (editing === null) await api.createGroup(name.trim(), startNumber);
			else
				await api.updateGroup(editing.id, {
					name: editing.fromLibrary ? undefined : name.trim(),
					start: startNumber === editing.start ? undefined : startNumber
				});
			open = false;
			await load();
		} catch (e) {
			problemText = problem(e);
		}
	}

	async function remove() {
		if (!editing) return;
		try {
			await api.deleteGroup(editing.id);
			open = false;
			await load();
		} catch (e) {
			problemText = problem(e);
		}
	}

	/** Moves [group] by [delta] places, in the list at once and on the TV. */
	async function move(group: Group, delta: number) {
		if (!groups) return;
		const from = groups.indexOf(group);
		const to = Math.min(Math.max(from + delta, 0), groups.length - 1);
		if (from === to) return;
		const reordered = [...groups];
		reordered.splice(to, 0, ...reordered.splice(from, 1));
		groups = reordered;
		try {
			await api.moveGroup(group.id, to - from);
		} catch (e) {
			error = problem(e);
			await load();
		}
	}

	function keys(event: KeyboardEvent, group: Group) {
		if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
			event.preventDefault();
			void move(group, event.key === 'ArrowUp' ? -1 : 1).then(() => {
				// Focus stays on the handle of the group carried.
				document.getElementById(`handle-${group.id}`)?.focus();
			});
		}
	}

	function drop(target: Group) {
		if (!groups || dragging === null) return;
		const carried = groups.find((g) => g.id === dragging);
		dragging = null;
		if (carried) void move(carried, groups.indexOf(target) - groups.indexOf(carried));
	}

	function summary(group: Group): string {
		const parts = [group.fromLibrary ? 'Library' : null, `starts at ${group.start}`, counted(group.channelCount, 'channel')].filter(Boolean).join(', ');
		return parts.charAt(0).toUpperCase() + parts.slice(1);
	}
</script>

<Page
	title="Groups"
	trail={[
		{ label: 'Home', href: '/' },
		{ label: 'Live TV', href: '/channels' }
	]}
	note="Each group is a tab in Live TV, in this order, and numbers its channels A to Z from its start. Move a group by its handle (or its handle and the up and down keys) to change the order. Library groups hold the channels made from your library. A group you made can be deleted once it is empty."
>
	{#snippet actions()}<Button label="New group" icon={CreateNewFolder} onclick={() => edit(null)} />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	{#if groups?.length === 0}<Notice text="No groups yet." />{/if}
	<ol class="m-0 flex list-none flex-col gap-0.5 p-0">
		{#each groups ?? [] as group (group.id)}
			<!-- A group in hand steps up to the raised surface, never a shadow. -->
			<li
				class={['flex items-center rounded-control transition-colors', dragging === group.id && 'bg-surface-high']}
				ondragover={(event) => dragging !== null && event.preventDefault()}
				ondrop={() => drop(group)}
			>
				<div class="min-w-0 flex-1">
					<Row title={group.name} summary={summary(group)} icon={group.fromLibrary ? FolderSpecial : Folder} onclick={() => edit(group)} aria-haspopup="dialog">
						{#snippet end()}<Edit class="size-6" />{/snippet}
					</Row>
				</div>
				<button
					id="handle-{group.id}"
					class="grid size-14 shrink-0 cursor-grab place-items-center rounded-control text-text-muted transition-colors hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent"
					draggable="true"
					aria-label="Move {group.name}. Up and down arrows move it."
					ondragstart={(event) => {
						dragging = group.id;
						event.dataTransfer?.setData('text/plain', String(group.id));
					}}
					ondragend={() => (dragging = null)}
					onkeydown={(event) => keys(event, group)}
				>
					<DragHandle class="size-6" />
				</button>
			</li>
		{/each}
	</ol>
</Page>

<Dialog bind:open title={editing ? `Edit ${editing.name}` : 'New group'} about="Its channels are numbered from the start.">
	<form class="flex flex-col gap-3" onsubmit={save}>
		{#if !editing?.fromLibrary}
			<!-- svelte-ignore a11y_autofocus -->
			<TextField label="Name" bind:value={name} maxlength={40} required autofocus />
		{/if}
		<TextField label="Start" bind:value={start} inputmode="numeric" maxlength={4} tabular required />
		{#if problemText}<div class="-mx-4"><Notice text={problemText} error /></div>{/if}
		<div class="flex flex-wrap gap-2 *:min-w-fit *:flex-1">
			<Button label="Cancel" onclick={() => (open = false)} />
			<Button label="Save" type="submit" primary />
		</div>
		{#if editing && !editing.fromLibrary && editing.channelCount === 0}
			<div class="flex *:flex-1"><Button label="Delete group" danger onclick={remove} /></div>
		{/if}
	</form>
</Dialog>
