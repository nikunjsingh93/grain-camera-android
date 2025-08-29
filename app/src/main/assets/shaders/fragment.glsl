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

// Grain (value 0..1)
float rand(vec2 co){
    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
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

    // Bright-pass for bloom/halation
    float bright = smoothstep(0.85, 1.0, Y);
    vec3 blurred = blur9(vTexCoord, 2.0 + 6.0 * (uParams.x + uParams.y));
    vec3 bloom = blurred * bright;
    // Halation: red-biased halo, scaled by halation param
    vec3 halation = vec3(bloom.r, bloom.g * 0.3, bloom.b * 0.2) * uParams.x;

    // Combine
    c += bloom * uParams.y + halation;

    // Grain
    float g = (rand(vTexCoord * uResolution + uTime*60.0) - 0.5) * uParams.z * 0.25;
    c += g;

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
