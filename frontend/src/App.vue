<script setup>
import { ref, computed, onMounted } from 'vue'

// Backend base URL is hardcoded per the spec. In a real codebase this would
// come from import.meta.env.VITE_API_BASE so prod/staging/dev each set their
// own — keeping it inline for the assignment.
const API_BASE = 'http://localhost:8080'

// ---- Currency list state ---------------------------------------------------
const currencies = ref([])
const loadingCurrencies = ref(true)
const currenciesError = ref(false)

// ---- Form state -----------------------------------------------------------
const source = ref('')
const target = ref('')
const amount = ref(100)

// ---- Conversion state -----------------------------------------------------
const converting = ref(false)
const result = ref(null)
const convertError = ref(null)

// Fetch the currency list once when the component is mounted. Pre-selecting
// EUR → USD only happens on success — on failure the dropdowns stay empty
// and the convert button still works (the backend will return a 400 we'll
// surface), so the failure mode is end-to-end visible.
onMounted(async () => {
  try {
    const response = await fetch(`${API_BASE}/api/currencies`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    currencies.value = await response.json()
    source.value = 'EUR'
    target.value = 'USD'
  } catch (e) {
    currenciesError.value = true
  } finally {
    loadingCurrencies.value = false
  }
})

async function convert() {
  // Clear any previous result/error so the UI doesn't show stale state
  // while the new request is in flight.
  converting.value = true
  result.value = null
  convertError.value = null

  try {
    const response = await fetch(`${API_BASE}/api/convert`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        amount: amount.value,
        sourceCurrency: source.value,
        targetCurrency: target.value
      })
    })
    const body = await response.json()
    if (response.ok) {
      result.value = body
    } else {
      // Backend's uniform error envelope: { status, errors: [...] }.
      // Join messages so a single field can render them inline.
      convertError.value = (body.errors || [`Request failed: ${response.status}`]).join('; ')
    }
  } catch (e) {
    // Network failure, CORS rejection, DNS — any pre-response error.
    convertError.value = 'Network error — is the API running on :8080?'
  } finally {
    converting.value = false
  }
}

// Flip source ↔ target. Common UX pattern in currency converters — after
// seeing USD→EUR a user often wants EUR→USD next. The previous result is
// cleared because it's now for the opposite direction and showing it
// alongside the new dropdown values would be misleading.
function swap() {
  [source.value, target.value] = [target.value, source.value]
  result.value = null
  convertError.value = null
}

// Browser-side locale-aware formatting via Intl.NumberFormat.
// undefined locale = use the browser's default.
const formattedAmount = computed(() =>
  result.value
    ? new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: result.value.targetCurrency
      }).format(result.value.convertedAmount)
    : ''
)
</script>

<template>
  <main>
    <h1>Currency Converter</h1>

    <form @submit.prevent="convert">
      <div class="field">
        <label for="source">From</label>
        <select id="source" v-model="source">
          <option v-if="loadingCurrencies" value="">Loading...</option>
          <option v-else-if="currenciesError" value="">Could not load currencies</option>
          <option v-for="c in currencies" :key="c.code" :value="c.code">
            {{ c.code }} — {{ c.name }}
          </option>
        </select>
      </div>

      <button
        type="button"
        class="swap"
        @click="swap"
        :disabled="converting || loadingCurrencies"
        aria-label="Swap source and target currencies"
        title="Swap currencies"
      >
        ⇅
      </button>

      <div class="field">
        <label for="target">To</label>
        <select id="target" v-model="target">
          <option v-if="loadingCurrencies" value="">Loading...</option>
          <option v-else-if="currenciesError" value="">Could not load currencies</option>
          <option v-for="c in currencies" :key="c.code" :value="c.code">
            {{ c.code }} — {{ c.name }}
          </option>
        </select>
      </div>

      <div class="field">
        <label for="amount">Amount</label>
        <input id="amount" type="number" v-model.number="amount" min="0" step="0.01" />
      </div>

      <button type="submit" :disabled="converting">
        {{ converting ? 'Converting...' : 'Convert' }}
      </button>
    </form>

    <div v-if="result" class="result">
      <p class="amount">{{ formattedAmount }}</p>
      <p class="rate">
        1 {{ result.sourceCurrency }} = {{ result.exchangeRate }} {{ result.targetCurrency }}
      </p>
    </div>

    <div v-if="convertError" class="error">{{ convertError }}</div>
  </main>
</template>

<style scoped>
main {
  max-width: 480px;
  margin: 2rem auto;
  padding: 0 1rem;
  font-family: system-ui, -apple-system, sans-serif;
  color: #222;
}

h1 {
  font-size: 1.5rem;
  margin-bottom: 1.5rem;
}

form {
  display: grid;
  gap: 1rem;
}

.field {
  display: grid;
  gap: 0.25rem;
}

label {
  font-size: 0.875rem;
  color: #555;
}

select,
input {
  padding: 0.5rem;
  font-size: 1rem;
  border: 1px solid #ccc;
  border-radius: 4px;
  background: white;
}

button {
  padding: 0.6rem;
  font-size: 1rem;
  background: #1f6feb;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

button:disabled {
  background: #aaa;
  cursor: not-allowed;
}

.swap {
  justify-self: center;
  width: 2.25rem;
  height: 2.25rem;
  padding: 0;
  font-size: 1.1rem;
  background: white;
  color: #1f6feb;
  border: 1px solid #ccc;
  border-radius: 50%;
  margin: -0.25rem 0;
}

.swap:hover:not(:disabled) {
  background: #f5f5f5;
}

.swap:disabled {
  background: white;
  color: #aaa;
  border-color: #ddd;
}

.result {
  margin-top: 1.5rem;
  padding: 1rem;
  background: #f5f5f5;
  border-radius: 4px;
}

.amount {
  font-size: 1.5rem;
  font-weight: 600;
  margin: 0;
}

.rate {
  margin: 0.5rem 0 0 0;
  color: #666;
  font-size: 0.875rem;
}

.error {
  margin-top: 1rem;
  padding: 0.75rem;
  background: #ffebee;
  color: #c62828;
  border-radius: 4px;
  font-size: 0.875rem;
}
</style>
