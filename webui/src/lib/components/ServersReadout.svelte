<script lang="ts">
	import type { Overview } from '#lib/api.ts';
	import { ago } from '#lib/format.ts';
	import Dns from '~icons/material-symbols/dns';
	import ErrorOutline from '~icons/material-symbols/error-outline';
	import Fact from './Fact.svelte';
	import Facts from './Facts.svelte';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import Rule from './Rule.svelte';
	import StatusLine from './StatusLine.svelte';

	/**
	 * All the servers as one readout, the same size however many there are: how their syncing
	 * stands, what their libraries hold together, and how many servers there are and how many need
	 * attention. Each server's own readout is on its page.
	 */
	let { servers }: { servers: Overview['servers'] } = $props();

	const several = $derived(servers.count > 1);
</script>

<Readout>
	{#if servers.count === 0}
		<Fact icon={Dns} text="No server yet" quiet />
	{:else}
		{#if servers.syncing > 0}
			<StatusLine label="Syncing" text={several ? `${servers.syncing} of ${servers.count}` : ''} state="off" />
		{:else if servers.needAttention > 0}
			<StatusLine label="Needs attention" text={several ? `${servers.needAttention} of ${servers.count}` : ''} state="problem" />
		{:else if servers.lastSyncUtcMs}
			<!-- The newest sync speaks for all of them. -->
			<StatusLine label="Synced" text={ago(servers.lastSyncUtcMs)} />
		{:else}
			<StatusLine label="Not synced yet" state="off" />
		{/if}
		<Figures>
			<Figure value={servers.movies} name={servers.movies === 1 ? 'movie' : 'movies'} />
			<Figure value={servers.series} name="series" />
			<Figure value={servers.episodes} name={servers.episodes === 1 ? 'episode' : 'episodes'} />
		</Figures>
		<Rule />
		<Facts>
			<Fact icon={Dns} text={String(servers.count)} detail={servers.count === 1 ? 'server' : 'servers'} />
			{#if servers.needAttention > 0}
				<Fact icon={ErrorOutline} text={String(servers.needAttention)} detail={servers.needAttention === 1 ? 'needs attention' : 'need attention'} />
			{/if}
		</Facts>
	{/if}
</Readout>
