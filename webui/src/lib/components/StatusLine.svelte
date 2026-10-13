<script lang="ts">
	import ErrorOutline from '~icons/material-symbols/error-outline';

	/**
	 * A status in the banner's NOW voice: a solid square when on, a hollow one when not yet, a
	 * warning mark for a problem, so each reads by its form as well as its color.
	 */
	let { label, text = '', state = 'on' }: { label: string; text?: string; state?: 'on' | 'off' | 'problem' } = $props();
</script>

<p class="field-label flex items-center gap-2">
	{#if state === 'problem'}
		<ErrorOutline class="size-4 text-accent" aria-label="Problem" />
	{:else}
		<span class={['size-2', state === 'off' ? 'border-[1.5px] border-accent' : 'bg-accent']} aria-hidden="true"></span>
	{/if}
	<span class="text-accent">{label}</span>
	{#if text}<span class="text-text-muted">{text}</span>{/if}
</p>
