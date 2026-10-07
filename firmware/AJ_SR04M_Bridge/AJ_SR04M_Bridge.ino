/*
 * BT Viewer — sensor firmware for a UART-to-BLE bridge setup
 *
 * This is your original working sketch with three changes and nothing else.
 * There is deliberately NO BLE code here: the HM-10 style module on the UART is
 * what advertises FFE0/FFE1 and carries the data. Adding an ESP32 BLE stack
 * would put a second, competing radio on the same board.
 *
 * Wiring and baud are untouched, so the bridge keeps working as it already did.
 *
 *   1. Prints the bare number and a newline — "87\n" — instead of
 *      "Distance: 87 cm". The app parses the whole line as a number.
 *   2. pulseIn gets a timeout. Without one it blocks for a full second every
 *      time an echo is lost, which near the ground is often.
 *   3. Rolling median of 3 rejects the spikes the AJ-SR04M throws, while still
 *      emitting after every ping rather than every third.
 */

#define echoPin 2   // Echo on the JSN/AJ-SR04M
#define trigPin 4   // Trig

// Match your bridge module's baud. HM-10 modules ship at 9600; this sketch was
// already running at 115200 and working, so the module is configured for it.
// If you ever reset the module to defaults, change this to 9600.
static const long BAUD = 115200;

static const float MIN_CM = 20.0f;
static const float MAX_CM = 450.0f;
static const uint32_t ECHO_TIMEOUT_US = (uint32_t)(MAX_CM * 2.0f / 0.0343f) + 2000;
static const uint16_t PING_GAP_MS = 10;
static const uint8_t WINDOW = 3;

static float ring[WINDOW];
static uint8_t ringN = 0, ringI = 0;

void setup() {
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);
  digitalWrite(trigPin, LOW);
  Serial.begin(BAUD);
  // No banner. Anything printed here goes straight down the bridge and reaches
  // the app as a line it cannot parse.
}

static float measureOnce() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(4);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long us = pulseIn(echoPin, HIGH, ECHO_TIMEOUT_US);
  if (us == 0) return -1.0f;
  return us / 58.0f;
}

static void push(float d) {
  ring[ringI] = d;
  ringI = (ringI + 1) % WINDOW;
  if (ringN < WINDOW) ringN++;
}

static float median() {
  if (ringN == 0) return -1.0f;
  float v[WINDOW];
  for (uint8_t i = 0; i < ringN; i++) v[i] = ring[i];
  for (uint8_t i = 1; i < ringN; i++) {
    float key = v[i];
    int8_t j = i - 1;
    while (j >= 0 && v[j] > key) { v[j + 1] = v[j]; j--; }
    v[j + 1] = key;
  }
  return v[ringN / 2];
}

void loop() {
  float raw = measureOnce();
  if (raw >= MIN_CM && raw <= MAX_CM) push(raw);

  float d = median();
  if (d > 0) {
    // Bare number, newline terminated. Nothing else on the wire.
    Serial.println((int)(d + 0.5f));
  } else {
    // -1 rather than silence. Saying nothing when there is no echo makes a dead
    // sensor look identical to a dead UART, and those need opposite fixes.
    Serial.println(-1);
  }
  delay(PING_GAP_MS);
}
