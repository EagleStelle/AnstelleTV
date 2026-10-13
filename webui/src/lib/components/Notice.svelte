<script lang="ts">
	import ErrorOutline from '~icons/material-symbols/error-outline';

	/** A line for a problem, a status or an empty list; problems carry a warning mark, not color alone. */
	let { text, error = false }: { text: string; error?: boolean } = $props();
</script>

<p class={['flex items-start gap-3 px-4 py-3 text-body-m', error ? 'text-text' : 'text-text-muted']} role={error ? 'alert' : 'status'}>
	{#if error}<ErrorOutline class="size-5 shrink-0 text-accent" aria-label="Problem" />{/if}
	<span>{text}</span>
</p>
