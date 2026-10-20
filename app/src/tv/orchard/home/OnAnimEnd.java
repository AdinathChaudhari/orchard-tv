package tv.orchard.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

import java.util.function.Consumer;

/**
 * An end listener built from a lambda. Top-level on purpose: javac 21 writes a nameless "mandated" MethodParameters
 * entry for an anonymous inner class's outer-instance parameter, and d8 8.2.2 crashes on it
 * (NullPointerException in String.length). Use lambdas, method references or static classes instead.
 */
final class OnAnimEnd extends AnimatorListenerAdapter {
    private final Consumer<Animator> end;

    OnAnimEnd(Consumer<Animator> end) { this.end = end; }

    @Override public void onAnimationEnd(Animator a) { end.accept(a); }
}
