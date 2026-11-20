package app.measure.ar.ar

import kotlin.math.sqrt

// Tiny 3-vector helpers over FloatArray(3), matching how ARCore hands back translations/axes.

internal fun vec(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
internal operator fun FloatArray.plus(o: FloatArray) = vec(this[0] + o[0], this[1] + o[1], this[2] + o[2])
internal operator fun FloatArray.minus(o: FloatArray) = vec(this[0] - o[0], this[1] - o[1], this[2] - o[2])
internal operator fun FloatArray.times(s: Float) = vec(this[0] * s, this[1] * s, this[2] * s)
internal operator fun FloatArray.unaryMinus() = vec(-this[0], -this[1], -this[2])
internal infix fun FloatArray.dot(o: FloatArray) = this[0] * o[0] + this[1] * o[1] + this[2] * o[2]
internal infix fun FloatArray.cross(o: FloatArray) = vec(
    this[1] * o[2] - this[2] * o[1],
    this[2] * o[0] - this[0] * o[2],
    this[0] * o[1] - this[1] * o[0],
)
internal fun FloatArray.length() = sqrt(this dot this)
internal fun FloatArray.distanceTo(o: FloatArray) = (this - o).length()
internal fun FloatArray.lerp(to: FloatArray, t: Float) = this + (to - this) * t

/** Unit vector in the same direction, or null if the vector is (nearly) zero. */
internal fun FloatArray.normalized(): FloatArray? {
    val len = length()
    return if (len < 1e-6f) null else this * (1f / len)
}

internal fun List<FloatArray>.centroid(): FloatArray =
    fold(vec(0f, 0f, 0f)) { acc, p -> acc + p } * (1f / size)
