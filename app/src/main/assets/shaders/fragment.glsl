#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;
// x=halation, y=bloom, z=grain, w=exposure
uniform vec4 uParams;
// x=contrast, y=saturation, z=shadowTint, w=highlightTint
uniform vec4 uFilm;
uniform int uShowRuleOfThirds;
// Grain size in pixels (1..N)
uniform float uGrainSize;
// Grain roughness (0..1) controls higher-frequency contribution
uniform float uGrainRoughness;

varying vec2 vTexCoord;

// Utility: luma
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Utility: softclip curve
vec3 toneMap(vec3 c, float contrast, float exposure) {
    c *= exp2(exposure); // exposure in stops
    c = (c - 0.5) * contrast + 0.5;
    return clamp(c, 0.0, 1.0);
}

// Utility: saturation
vec3 sat(vec3 c, float s) {
    float Y = luma(c);
    return mix(vec3(Y), c, s);
}

// Simple box blur around a pixel for bloom/halation approx
vec3 blur9(vec2 uv, float radius) {
    vec2 texel = 1.0 / uResolution;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int x=-1; x<=1; x++) {
        for (int y=-1; y<=1; y++) {
            vec2 offs = vec2(float(x), float(y)) * texel * radius;
            float w = 1.0; // box weights
            acc += texture2D(sTexture, uv + offs).rgb * w;
            wsum += w;
        }
    }
    return acc / wsum;
}

// Hash-based value noise and fbm for film-like grain
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
float valueNoise(vec2 p){
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p, float rough){
    float sum = 0.0;
    float amp = 0.6;
    float freq = 1.0;
    // roughness increases higher octave weights
    float r2 = clamp(rough, 0.0, 1.0);
    for (int i = 0; i < 4; i++) {
        sum += valueNoise(p * freq) * amp;
        freq *= 2.0;
        amp *= mix(0.45, 0.75, r2);
    }
    return sum;
}

void main() {
    vec3 base = texture2D(sTexture, vTexCoord).rgb;

    // Film simulation base: contrast/saturation + gentle split-tone
    float contrast = uFilm.x;
    float saturation = uFilm.y;
    float shadowTint = uFilm.z;   // + warm, - cool
    float highlightTint = uFilm.w; // + warm, - cool

    vec3 c = base;
    c = toneMap(c, contrast, uParams.w);
    c = sat(c, saturation);

    float Y = luma(c);
    // Split tone: warm highlights, cool shadows depending on film
    vec3 shadowColor = mix(vec3(1.0), vec3(1.0, 0.95, 0.9), max(0.0, shadowTint));
    shadowColor = mix(shadowColor, vec3(0.9, 0.95, 1.05), max(0.0, -shadowTint));

    vec3 highlightColor = mix(vec3(1.0), vec3(1.05, 1.0, 0.95), max(0.0, highlightTint));
    highlightColor = mix(highlightColor, vec3(0.95, 1.0, 1.05), max(0.0, -highlightTint));

    c = mix(c * shadowColor, c * highlightColor, smoothstep(0.35, 0.65, Y));

    // Bright-pass for bloom/halation with slightly softer transition for halation
    float brightBloom = smoothstep(0.85, 1.0, Y);
    float brightHalation = smoothstep(0.80, 1.0, Y);

    // Use separate blur radii to get softer, wider halation edges
    float radiusBloom = 2.0 + 6.0 * uParams.y;
    float radiusHalation = 3.0 + 10.0 * uParams.x;
    vec3 blurredBloom = blur9(vTexCoord, radiusBloom);
    vec3 blurredHalation = blur9(vTexCoord, radiusHalation);
    vec3 bloom = blurredBloom * brightBloom;
    // Halation: red-biased halo, scaled by halation param and halation bright-pass
    vec3 halation = vec3(blurredHalation.r, blurredHalation.g * 0.3, blurredHalation.b * 0.2) * (uParams.x * brightHalation);

    // Combine
    c += bloom * uParams.y + halation;

    // Film-like grain via fbm value noise. Stable in screen space, animated slowly.
    float gs = max(1.0, uGrainSize);
    vec2 grainBase = (vTexCoord * uResolution) / gs;
    float ang = 0.37 * uTime; // slight rotation to break grid
    mat2 rot = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
    vec2 gc = rot * grainBase + vec2(0.0, uTime * 0.25);
    float n = fbm(gc, uGrainRoughness);
    float g = (n - 0.5);
    // Modulate visibility: more in shadows/midtones, less in bright highlights
    float vis = mix(1.0, 0.6, smoothstep(0.65, 1.0, Y));
    vec3 grainColor = vec3(0.98, 1.0, 1.02); // subtle chroma variance
    c += grainColor * (g * uParams.z * 0.35 * vis);

    // Rule of thirds overlay
    if (uShowRuleOfThirds == 1) {
        vec2 uv = vTexCoord;
        float lineWidth = 1.0 / uResolution.x * 2.0; // 2 pixel wide lines
        
        // Vertical lines at 1/3 and 2/3
        float v1 = abs(uv.x - 0.333333);
        float v2 = abs(uv.x - 0.666667);
        
        // Horizontal lines at 1/3 and 2/3
        float h1 = abs(uv.y - 0.333333);
        float h2 = abs(uv.y - 0.666667);
        
        // Check if we're on any of the rule of thirds lines
        if (v1 < lineWidth || v2 < lineWidth || h1 < lineWidth || h2 < lineWidth) {
            c = mix(c, vec3(1.0, 1.0, 1.0), 0.3); // White overlay with 30% opacity
        }
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
