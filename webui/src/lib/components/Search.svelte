<script lang="ts">
	import SearchIcon from '~icons/material-symbols/search';

	/** A search over a list too long to scan. */
	let { value = $bindable(), label }: { value: string; label: string } = $props();
</script>

<label class="mx-4 my-2 flex min-h-12 items-center gap-3 rounded-control border border-line bg-background px-3.5 text-text-muted focus-within:border-accent focus-within:text-accent">
	<SearchIcon class="size-6 shrink-0" />
	<span class="sr-only">{label}</span>
	<input type="search" bind:value placeholder={label} class="min-w-0 flex-1 bg-transparent text-text caret-accent placeholder:text-text-muted" />
</label>
