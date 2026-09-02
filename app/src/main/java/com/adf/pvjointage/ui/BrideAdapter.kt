package com.adf.pvjointage.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.adf.pvjointage.R
import com.adf.pvjointage.data.BrideCatalog
import com.adf.pvjointage.data.InspectionResult
import com.adf.pvjointage.databinding.ItemBrideBinding
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.Etat

data class BrideRow(val bride: BrideCatalog, val inspection: InspectionResult?)

class BrideAdapter(
    private val onClick: (BrideCatalog) -> Unit
) : RecyclerView.Adapter<BrideAdapter.VH>() {

    private var rows: List<BrideRow> = emptyList()

    fun submit(newRows: List<BrideRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBrideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position], onClick)
    }

    override fun getItemCount() = rows.size

    class VH(private val binding: ItemBrideBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BrideRow, onClick: (BrideCatalog) -> Unit) {
            val ctx = binding.root.context
            val b = row.bride
            binding.tvRep.text = b.rep
            binding.tvDesignation.text = b.designation
            binding.tvDetails.text = "DN ${b.dn} • PN ${b.pn} • ${b.matiereJoint}"

            val insp = row.inspection
            val etiquette = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.etiquette(
                Etat.fromCode(insp.etiMiseSerree), Etat.fromCode(insp.etiNomDateLisible)
            )
            val joint = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.joint(
                Etat.fromCode(insp.jointMatiereConforme), Etat.fromCode(insp.jointDimensionCentrage), Etat.fromCode(insp.jointAspectNeuf)
            )
            val boulonnerie = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.boulonnerie(
                Etat.fromCode(insp.boulonNeuves), Etat.fromCode(insp.boulonRondelles),
                Etat.fromCode(insp.boulonEquilibrage), Etat.fromCode(insp.boulonGraissage),
                Etat.fromCode(insp.boulonLongueurDiametre), Etat.fromCode(insp.boulonMatiere)
            )
            val assemblage = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.assemblage(
                Etat.fromCode(insp.assemblageParallelisme), Etat.fromCode(insp.assemblageExcentration)
            )
            val global = ConformiteCalculator.global(etiquette, joint, boulonnerie, assemblage)

            setChip(ctx, binding.tvEtiquette, etiquette)
            setChip(ctx, binding.tvJoint, joint)
            setChip(ctx, binding.tvBoulonnerie, boulonnerie)
            setChip(ctx, binding.tvAssemblage, assemblage)
            setChip(ctx, binding.tvConforme, global, withLabel = true)

            binding.rowRoot.setOnClickListener { onClick(b) }
        }

        private fun setChip(ctx: Context, tv: android.widget.TextView, c: Conformite, withLabel: Boolean = false) {
            val (color, text) = when (c) {
                Conformite.CONFORME -> R.color.conforme to (if (withLabel) ctx.getString(R.string.statut_conforme) else "C")
                Conformite.NON_CONFORME -> R.color.non_conforme to (if (withLabel) ctx.getString(R.string.statut_non_conforme) else "NC")
                Conformite.EN_ATTENTE -> R.color.en_attente to (if (withLabel) ctx.getString(R.string.statut_attente) else "-")
            }
            tv.setBackgroundColor(ContextCompat.getColor(ctx, color))
            tv.text = text
        }
    }
}
