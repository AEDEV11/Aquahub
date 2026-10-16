/* Progressive, opt-in motion for the AquaHub marketing page. */
(() => {
  "use strict";

  const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
  const query = (value) => window.matchMedia?.(value) ?? null;
  const reducedMotion = query("(prefers-reduced-motion: reduce)");
  const smallScreen = query("(max-width: 767px)");
  const connection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;

  let hero = null;
  let stories = [];
  let revealObserver = null;
  let canvas = null;
  let canvasObserver = null;
  let canvasResizeObserver = null;
  let canvasContext = null;
  let canvasVisible = false;
  let canvasWidth = 0;
  let canvasHeight = 0;
  let particles = [];
  let lastCanvasTime = 0;
  let frameId = 0;
  let layoutDirty = true;
  let canvasSizeDirty = true;
  let initialized = false;

  const prefersLessMotion = () => reducedMotion?.matches === true;
  const canvasDisabled = () => prefersLessMotion()
    || smallScreen?.matches === true
    || connection?.saveData === true;

  function onMediaChange(media, callback) {
    if (!media) return;
    if (media.addEventListener) media.addEventListener("change", callback);
    else media.addListener?.(callback);
  }

  function scheduleFrame() {
    if (!frameId) frameId = window.requestAnimationFrame(onFrame);
  }

  function requestLayout() {
    layoutDirty = true;
    scheduleFrame();
  }

  function setActiveScene(story, scene) {
    if (story.activeScene === scene) return;
    story.activeScene = scene;
    story.root.dataset.activeScene = String(scene);
    story.steps.forEach((item) => item.element.classList.toggle("is-active", item.scene === scene));
    story.visuals.forEach((item) => item.element.classList.toggle("is-active", item.scene === scene));
  }

  function updateLayout() {
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 1;
    const midpoint = viewportHeight / 2;

    if (hero) {
      const rect = hero.getBoundingClientRect();
      const distance = Math.max(1, rect.height - viewportHeight * 0.3);
      const progress = prefersLessMotion() ? 0 : clamp(-rect.top / distance);
      hero.style.setProperty("--hero-progress", progress.toFixed(3));
    }

    stories.forEach((story) => {
      const rect = story.root.getBoundingClientRect();
      const progress = prefersLessMotion() ? 0 : clamp((midpoint - rect.top) / Math.max(1, rect.height));
      story.root.style.setProperty("--story-progress", progress.toFixed(3));
      const stageBottom = story.stage?.getBoundingClientRect().bottom ?? 0;
      const focusY = window.innerWidth <= 700
        ? Math.max(midpoint, stageBottom + (viewportHeight - stageBottom) * 0.5)
        : midpoint;

      let closest = null;
      let closestDistance = Infinity;
      story.steps.forEach((item) => {
        const stepRect = item.element.getBoundingClientRect();
        const distance = Math.abs(stepRect.top + stepRect.height / 2 - focusY);
        if (distance < closestDistance) {
          closest = item;
          closestDistance = distance;
        }
      });
      if (closest) setActiveScene(story, closest.scene);
    });

    if (canvas && !canvasObserver) {
      const rect = canvas.getBoundingClientRect();
      canvasVisible = rect.bottom > 0 && rect.top < viewportHeight && rect.right > 0 && rect.left < window.innerWidth;
    }
  }

  function makeParticle(width, height) {
    return {
      x: Math.random(),
      y: Math.random(),
      radius: 1.4 + Math.random() * 3.2,
      speed: (14 + Math.random() * 24) / Math.max(1, height),
      drift: (5 + Math.random() * 10) / Math.max(1, width),
      phase: Math.random() * Math.PI * 2,
      alpha: 0.12 + Math.random() * 0.16,
    };
  }

  function sizeCanvas() {
    canvasSizeDirty = false;
    if (!canvas || !canvasContext) return;

    const rect = canvas.getBoundingClientRect();
    const width = Math.max(0, Math.round(rect.width));
    const height = Math.max(0, Math.round(rect.height));
    if (width === canvasWidth && height === canvasHeight) return;

    canvasWidth = width;
    canvasHeight = height;
    if (!width || !height) return;

    const dpr = Math.min(
      window.devicePixelRatio || 1,
      1.5,
      Math.sqrt(2_000_000 / (width * height)),
    );
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));
    canvasContext.setTransform(dpr, 0, 0, dpr, 0, 0);
    particles = Array.from({ length: Math.min(30, Math.max(14, Math.round(width / 55))) },
      () => makeParticle(width, height));
  }

  function clearCanvas() {
    if (canvasContext && canvasWidth && canvasHeight) {
      canvasContext.clearRect(0, 0, canvasWidth, canvasHeight);
    }
    lastCanvasTime = 0;
  }

  function canDrawCanvas() {
    return !!(canvasContext && canvasVisible && canvasWidth && canvasHeight
      && !document.hidden && !canvasDisabled());
  }

  function drawCanvas(now) {
    if (!canvasContext) return;
    const seconds = lastCanvasTime ? Math.min((now - lastCanvasTime) / 1000, 0.05) : 0;
    lastCanvasTime = now;
    canvasContext.clearRect(0, 0, canvasWidth, canvasHeight);

    const progress = hero ? Number.parseFloat(hero.style.getPropertyValue("--hero-progress")) || 0 : 0;
    const time = now / 1000;

    // Wide, quiet flow lines sit behind the sparse bubbles.
    for (let index = 0; index < 3; index += 1) {
      const y = canvasHeight * (0.3 + index * 0.22) + Math.sin(time * 0.45 + index) * 5;
      canvasContext.beginPath();
      canvasContext.moveTo(-20, y);
      canvasContext.bezierCurveTo(
        canvasWidth * 0.28, y - 20 - progress * 9,
        canvasWidth * 0.65, y + 17 + progress * 9,
        canvasWidth + 20, y - 4,
      );
      canvasContext.strokeStyle = `rgba(18, 100, 230, ${0.055 + index * 0.012})`;
      canvasContext.lineWidth = 1;
      canvasContext.stroke();
    }

    particles.forEach((particle, index) => {
      particle.y -= particle.speed * seconds * (1 + progress * 0.4);
      particle.x += Math.sin(time * 0.6 + particle.phase) * particle.drift * seconds
        + progress * 0.006 * seconds;
      if (particle.y < -0.03) particle.y = 1.03;
      if (particle.x > 1.03) particle.x = -0.03;
      if (particle.x < -0.03) particle.x = 1.03;

      const x = particle.x * canvasWidth;
      const y = particle.y * canvasHeight;
      const radius = particle.radius * (1 + progress * 0.2);
      canvasContext.beginPath();
      canvasContext.arc(x, y, radius, 0, Math.PI * 2);
      canvasContext.fillStyle = `rgba(255, 255, 255, ${particle.alpha * 0.6})`;
      canvasContext.fill();
      canvasContext.strokeStyle = `rgba(18, 100, 230, ${particle.alpha})`;
      canvasContext.lineWidth = 1;
      canvasContext.stroke();

      if (index % 8 === 0) {
        canvasContext.beginPath();
        canvasContext.arc(x, y, radius + 7, -0.9, 0.7);
        canvasContext.strokeStyle = `rgba(22, 199, 232, ${particle.alpha * 0.5})`;
        canvasContext.stroke();
      }
    });
  }

  function onFrame(now) {
    frameId = 0;
    if (layoutDirty) {
      layoutDirty = false;
      updateLayout();
    }
    if (canvasSizeDirty && canvasVisible && !canvasDisabled()) sizeCanvas();
    if (canDrawCanvas()) {
      drawCanvas(now);
      scheduleFrame();
    } else {
      lastCanvasTime = 0;
    }
  }

  function showAllReveals() {
    revealObserver?.disconnect();
    document.querySelectorAll("[data-reveal]").forEach((element) => element.classList.add("is-visible"));
  }

  function initReveals(reveals) {
    if (!reveals.length) return;
    if (prefersLessMotion() || !window.IntersectionObserver) {
      showAllReveals();
      return;
    }

    revealObserver = new IntersectionObserver((entries, observer) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -8% 0px" });

    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    reveals.forEach((element) => {
      const rect = element.getBoundingClientRect();
      if (rect.top < viewportHeight * 0.92 && rect.bottom > 0) {
        element.classList.add("is-visible");
      } else {
        revealObserver.observe(element);
      }
    });
  }

  function initCanvas() {
    if (!canvas) return;
    canvas.setAttribute("aria-hidden", "true");
    canvas.style.pointerEvents = "none";
    canvasContext = canvas.getContext("2d", { alpha: true });
    if (!canvasContext) {
      canvas.style.visibility = "hidden";
      return;
    }

    if (window.IntersectionObserver) {
      canvasObserver = new IntersectionObserver((entries) => {
        canvasVisible = entries[0]?.isIntersecting === true;
        if (!canvasVisible) clearCanvas();
        scheduleFrame();
      }, { threshold: 0.01 });
      canvasObserver.observe(canvas);
    }

    if (window.ResizeObserver) {
      canvasResizeObserver = new ResizeObserver(() => {
        canvasSizeDirty = true;
        scheduleFrame();
      });
      canvasResizeObserver.observe(canvas);
    }

    updateCanvasPreference();
  }

  function updateCanvasPreference() {
    if (!canvas) return;
    canvas.style.visibility = canvasDisabled() || !canvasContext ? "hidden" : "";
    if (canvasDisabled()) clearCanvas();
    scheduleFrame();
  }

  function onPreferenceChange() {
    if (prefersLessMotion()) showAllReveals();
    updateCanvasPreference();
    requestLayout();
  }

  function init() {
    if (initialized) return;
    initialized = true;

    hero = document.querySelector("[data-hero-scene]");
    stories = [...document.querySelectorAll("[data-scroll-story]")].map((root) => {
      const readItems = (selector) => [...root.querySelectorAll(selector)]
        .map((element) => ({ element, scene: Number(element.dataset.scene) }))
        .filter((item) => Number.isInteger(item.scene) && item.scene >= 0 && item.scene <= 3);
      return {
        root,
        stage: root.querySelector(".home-story-stage"),
        steps: readItems("[data-story-step][data-scene]"),
        visuals: readItems("[data-story-visual][data-scene]"),
        activeScene: null,
      };
    }).filter((story) => story.steps.length > 0);
    const reveals = [...document.querySelectorAll("[data-reveal]")];
    canvas = document.querySelector("[data-water-canvas]");

    if (!hero && !stories.length && !reveals.length && !canvas) return;

    initReveals(reveals);
    initCanvas();
    updateLayout();
    layoutDirty = false;
    document.documentElement.classList.add("motion-ready");

    if (hero || stories.length || canvas) {
      window.addEventListener("scroll", requestLayout, { passive: true });
      window.addEventListener("resize", () => {
        canvasSizeDirty = true;
        requestLayout();
      }, { passive: true });
      window.addEventListener("pageshow", requestLayout);
    }
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) clearCanvas();
      else requestLayout();
    });
    onMediaChange(reducedMotion, onPreferenceChange);
    onMediaChange(smallScreen, onPreferenceChange);
    connection?.addEventListener?.("change", onPreferenceChange);
    scheduleFrame();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
