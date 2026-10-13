<script lang="ts">
	import type { Web } from '#lib/api.ts';
	import Computer from '~icons/material-symbols/computer';
	import Fact from './Fact.svelte';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import Rule from './Rule.svelte';
	import StatusLine from './StatusLine.svelte';

	/** Whether the web UI runs, where to open it, and the browsers paired with it. */
	let { web }: { web: Web } = $props();

	const SHOWN = 4;
	const url = $derived(web.running ? web.urls[0]?.replace(/^http:\/\//, '') : undefined);
</script>

<Readout>
	<StatusLine label={web.running ? 'On' : 'Not running'} state={web.running ? 'on' : 'off'} />
	{#if url}<p class="truncate font-display text-headline-s font-semibold">{url}</p>{/if}
	<Figures><Figure value={web.clients.length} name={web.clients.length === 1 ? 'paired browser' : 'paired browsers'} /></Figures>
	{#if web.clients.length > 0}
		<Rule />
		<div class="flex flex-col gap-2">
			{#each web.clients.slice(0, SHOWN) as client (client.id)}<Fact icon={Computer} text={client.name} detail={client.address} />{/each}
		</div>
	{/if}
</Readout>
