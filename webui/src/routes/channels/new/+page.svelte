<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Group, type Lineup, type Reach } from '#lib/api.ts';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import LineupBuilder from '#lib/components/LineupBuilder.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import ReachReadout from '#lib/components/ReachReadout.svelte';
	import TextRow from '#lib/components/TextRow.svelte';
	import Folder from '~icons/material-symbols/folder';
	import CreateNewFolder from '~icons/material-symbols/create-new-folder';
	import Title from '~icons/material-symbols/title';
	import { onMount } from 'svelte';

	let name = $state('');
	let groups = $state<Group[] | null>(null);
	let groupId = $state<number | null>(null);
	let lineup = $state<Lineup>({ groups: [], includes: [], excludes: [] });
	let reach = $state<Reach | null>(null);
	let error = $state<string | null>(null);

	onMount(async () => {
		try {
			// A channel someone builds goes into a group someone made, at first the one the last went into.
			const result = await api.groups();
			groups = result.groups.filter((g) => !g.fromLibrary);
			groupId = groups.find((g) => g.id === result.lastGroupId)?.id ?? groups[0]?.id ?? null;
		} catch (e) {
			error = problem(e);
		}
	});

	async function create(current: Lineup): Promise<string | null> {
		if (groupId === null) return 'Make a group first, under Live TV, Groups.';
		try {
			const { id } = await api.createChannel(name.trim() || 'New channel', groupId, current);
			await goto(`/channels/${id}`);
			return null;
		} catch (e) {
			return problem(e);
		}
	}
</script>

<Page
	title="New channel"
	trail={[
		{ label: 'Home', href: '/' },
		{ label: 'Live TV', href: '/channels' }
	]}
	note="Name it, pick its group, then add rules. A series or movie plays when it matches every rule in a set."
>
	{#snippet aside()}<ReachReadout {reach} />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	<LineupBuilder bind:lineup bind:reach saveLabel="Create channel" onsave={create}>
		{#snippet head()}
			<TextRow
				title="Name"
				value={name}
				summary={name || 'New channel'}
				icon={Title}
				onsave={(value) => {
					name = value;
					return null;
				}}
			/>
			{#if groups && groups.length > 0}
				<ChoiceRow
					title="Group"
					value={groups.find((g) => g.id === groupId) ?? groups[0]}
					choices={groups}
					label={(g) => g.name}
					hint={(g) => `Numbers from ${g.start}`}
					same={(a, b) => a.id === b.id}
					onselect={(g) => (groupId = g.id)}
					icon={Folder}
				/>
			{:else if groups}
				<LinkRow title="Group" summary="Make a group first" icon={CreateNewFolder} href="/channels/groups" />
			{/if}
		{/snippet}
	</LineupBuilder>
</Page>
