<script lang="ts">
	import { api, problem, type Channel } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Section from '#lib/components/Section.svelte';
	import { counted } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import Add from '~icons/material-symbols/add';
	import Edit from '~icons/material-symbols/edit';
	import Folder from '~icons/material-symbols/folder';
	import VideoLibrary from '~icons/material-symbols/video-library';
	import { onMount } from 'svelte';

	let channels = $state<Channel[] | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			channels = (await api.channels()).channels;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['channel', 'channel_group'], () => void load());
	});

	const custom = $derived((channels ?? []).filter((c) => !c.fromLibrary).length);
	const library = $derived((channels ?? []).filter((c) => c.fromLibrary).length);
	const count = (n: number) => (n === 0 ? 'None yet' : counted(n, 'channel'));
</script>

<!-- A way into each kind of channel, so a long custom list never stands between you and the library's. -->
<Page title="Live TV" trail={[{ label: 'Home', href: '/' }]} note="Shared by every profile. Groups are the tabs in Live TV and number their channels.">
	{#snippet actions()}<Button label="New channel" icon={Add} primary href="/channels/new" />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	<Section title="Configuration" />
	<LinkRow title="Groups" icon={Folder} href="/channels/groups" />
	{#if channels}
		<Section title="Channels" />
		<LinkRow title="Custom" summary={count(custom)} icon={Edit} href="/channels/custom" />
		<LinkRow title="Library" summary={count(library)} icon={VideoLibrary} href="/channels/library" />
	{/if}
</Page>
