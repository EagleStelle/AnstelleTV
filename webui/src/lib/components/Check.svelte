<script lang="ts">
	import CheckIcon from '~icons/material-symbols/check';
	import Remove from '~icons/material-symbols/remove';

	/** Whether something is kept or sent: a check or a dash before its name, so it reads without color. */
	let { on, name }: { on: boolean; name: string } = $props();
</script>

<p class="flex items-center gap-2 text-body-m">
	{#if on}
		<CheckIcon class="size-4.5 shrink-0 text-accent" aria-label="Yes" role="img" />
	{:else}
		<Remove class="size-4.5 shrink-0 text-text-muted" aria-label="No" role="img" />
	{/if}
	<span class={on ? 'text-text' : 'text-text-muted'}>{name}</span>
</p>
