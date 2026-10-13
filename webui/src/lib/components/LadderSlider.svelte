<script lang="ts">
	/**
	 * A value on a ladder: the arrow keys step it, a click or a drag along the ladder sets it. A
	 * native range input carries it, so keyboards and screen readers get it as a slider. Flat on
	 * its ground like every settings row; focus fills it.
	 */
	interface Props {
		label: string;
		/** What the value reads as ("54%", "120°"). */
		text: string;
		value: number;
		min: number;
		max: number;
		step: number;
		onchange: (value: number) => void;
		segments?: number;
	}

	let { label, text, value, min, max, step, onchange, segments = 32 }: Props = $props();
	const filled = $derived(Math.round(((value - min) / (max - min)) * segments));
</script>

<label
	class="group/ladder flex flex-col gap-1.5 rounded-control px-4 py-2.5 transition-colors focus-within:bg-accent focus-within:text-on-accent hover:bg-accent hover:text-on-accent"
>
	<span class="flex justify-between">
		<span class="font-medium">{label}</span>
		<span class="tabular text-label-l font-semibold">{text}</span>
	</span>
	<span class="relative h-6">
		<span class="absolute inset-x-0 inset-y-1.75 flex gap-0.5" aria-hidden="true">
			{#each Array(segments) as _, i (i)}
				<i
					class={[
						'flex-1',
						i < filled
							? 'bg-accent group-hover/ladder:bg-on-accent group-focus-within/ladder:bg-on-accent'
							: 'bg-line group-hover/ladder:bg-on-accent/25 group-focus-within/ladder:bg-on-accent/25'
					]}
				></i>
			{/each}
		</span>
		<input
			type="range"
			{min}
			{max}
			{step}
			{value}
			aria-valuetext={text}
			oninput={(e) => onchange(Number(e.currentTarget.value))}
			class="absolute inset-0 m-0 size-full cursor-pointer opacity-0"
		/>
	</span>
</label>
