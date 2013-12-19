package org.wikipedia.miner.extract.finalSummary;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.FileReader;
import org.apache.avro.file.SeekableInput;
import org.apache.avro.io.DatumReader;
import org.apache.avro.mapred.FsInput;
import org.apache.avro.mapred.Pair;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.record.CsvRecordOutput;
import org.apache.hadoop.record.Record;
import org.wikipedia.miner.db.struct.DbIntList;
import org.wikipedia.miner.db.struct.DbLabelForPage;
import org.wikipedia.miner.db.struct.DbLabelForPageList;
import org.wikipedia.miner.db.struct.DbLinkLocation;
import org.wikipedia.miner.db.struct.DbLinkLocationList;
import org.wikipedia.miner.db.struct.DbPage;
import org.wikipedia.miner.extract.LocalStep;
import org.wikipedia.miner.extract.Step;
import org.wikipedia.miner.extract.model.struct.LabelSenseList;
import org.wikipedia.miner.extract.model.struct.LabelSummary;
import org.wikipedia.miner.extract.model.struct.LinkSummary;
import org.wikipedia.miner.extract.model.struct.PageDepthSummary;
import org.wikipedia.miner.extract.model.struct.PageDetail;
import org.wikipedia.miner.extract.model.struct.PageSummary;
import org.wikipedia.miner.extract.pageDepth.PageDepthStep;
import org.wikipedia.miner.extract.sortedPages.PageSortingStep;
import org.wikipedia.miner.extract.util.SiteInfo;
import org.wikipedia.miner.model.Page.PageType;

public class FinalSummaryStep extends LocalStep {

	private PageSortingStep pageSortingStep ;
	private PageDepthStep pageDepthStep ;
	
	
	private Comparator<DbLabelForPage> labelComparator = new Comparator<DbLabelForPage>() {
		
		public int compare(DbLabelForPage a, DbLabelForPage b) {

			int cmp = new Long(b.getLinkOccCount()).compareTo(a.getLinkOccCount()) ;
			if (cmp != 0)
				return cmp ;

			cmp = new Long(b.getLinkDocCount()).compareTo(a.getLinkDocCount()) ;
			if (cmp != 0)
				return cmp ;

			return(a.getText().compareTo(b.getText())) ;
		}
	} ;
	
	
	
	public FinalSummaryStep(Path workingDir, PageSortingStep pageSortingStep, PageDepthStep pageDepthStep) throws IOException {
		super(workingDir);
		this.pageSortingStep = pageSortingStep ;
		this.pageDepthStep = pageDepthStep ;
	}

	@Override
	public int run() throws Exception {
		if (isFinished()) {
			return 0 ;
		} else
			reset() ;
		
		finalizePageStuff() ;
		
		finish() ;
		return 0 ;
	}
	
	
	
	public void finalizePageStuff() throws IOException {
		
		BufferedWriter pageWriter = createWriter("page.csv") ;
		
		
		BufferedWriter articleParentsWriter = createWriter("articleParents.csv") ;
		BufferedWriter categoryParentsWriter = createWriter("categoryParents.csv") ;
		BufferedWriter childArticlesWriter = createWriter("childArticles.csv") ;
		BufferedWriter childCategoriesWriter = createWriter("childCategories.csv") ;
		
		BufferedWriter pageLabelWriter = createWriter("pageLabel.csv") ;
		
		BufferedWriter pageLinkInWriter = createWriter("pageLinkIn.csv") ;
		BufferedWriter pageLinkOutWriter = createWriter("pageLinkOut.csv") ;
		
		BufferedWriter redirectSourcesByTargetWriter = createWriter("redirectSourcesByTarget.csv") ;
		BufferedWriter redirectTargetsBySourceWriter = createWriter("redirectTargetsBySource.csv") ;
		
		BufferedWriter sentenceSplitsWriter = createWriter("sentenceSplits.csv") ;
		
		
		Path pageDetailPath = getMainAvroResultPath(pageSortingStep) ;
		SeekableInput pageDetailInput = new FsInput(pageDetailPath, new Configuration());
		
		Schema pageDetailSchema = Pair.getPairSchema(Schema.create(Type.INT),PageDetail.getClassSchema()) ;
		DatumReader<Pair<Integer,PageDetail>> pageDetailDatumReader = new SpecificDatumReader<Pair<Integer,PageDetail>>(pageDetailSchema);
		FileReader<Pair<Integer,PageDetail>> pageDetailReader = DataFileReader.openReader(pageDetailInput, pageDetailDatumReader) ;
		
		
		
		Path pageDepthsPath = getMainAvroResultPath(pageDepthStep) ;
		SeekableInput pageDepthsInput = new FsInput(pageDepthsPath, new Configuration());
		
		Schema pageDepthsSchema = Pair.getPairSchema(Schema.create(Type.INT),PageDepthSummary.getClassSchema()) ;
		DatumReader<Pair<Integer,PageDepthSummary>> pageDepthsDatumReader = new SpecificDatumReader<Pair<Integer,PageDepthSummary>>(pageDepthsSchema);
		FileReader<Pair<Integer,PageDepthSummary>> pageDepthsReader = DataFileReader.openReader(pageDepthsInput, pageDepthsDatumReader) ;
		

		//read through pageDetail and pageDepth files simultaneously.
		//both are sorted by id, but pageDepth will be missing many entries.
		
		Pair<Integer,PageDetail> detailPair = null ;
		Pair<Integer,PageDepthSummary> depthPair = null ;
		while (pageDetailReader.hasNext()) {
			
			detailPair = pageDetailReader.next();
			
			while ((depthPair == null || depthPair.key() < detailPair.key()) && pageDepthsReader.hasNext())
				depthPair = pageDepthsReader.next();
			
			PageDetail detail = detailPair.value() ;
			
			PageDepthSummary depth = null ;
			if (depthPair.key().equals(detailPair.key()))
				depth = depthPair.value() ;
			
			
			//now we definitely have a page. If we have a depth, then it is synchonised with page
			
			DbPage page = buildPage(detail, depth) ;
			write(detail.getId(), page, pageWriter) ;
			
			if (detail.getNamespace() == SiteInfo.MAIN_KEY) {
				
				if (detail.getRedirectsTo() == null) {
			
					//this is an article or disambig
					DbIntList articleParents = buildIntList(detail.getParentCategories()) ;
					write(detail.getId(),articleParents, articleParentsWriter) ;
			
					DbLinkLocationList linksIn = buildLinkLocationList(detail.getLinksIn());
					write(detail.getId(), linksIn, pageLinkInWriter) ;
					
					DbLinkLocationList linksOut = buildLinkLocationList(detail.getLinksOut());
					write(detail.getId(), linksOut, pageLinkOutWriter) ;
					
					DbIntList redirectSources = buildIntList(detail.getRedirects()) ;
					write(detail.getId(),redirectSources, redirectSourcesByTargetWriter) ;
					
					DbIntList sentenceSplits = buildIntList(detail.getSentenceSplits()) ;
					write(detail.getId(),sentenceSplits, sentenceSplitsWriter) ;
					
					DbLabelForPageList labels = buildLabelList(detail) ;
					write(detail.getId(), labels, pageLabelWriter) ;
					
					
				} else {
					//this is a redirect
					
					redirectTargetsBySourceWriter.write(detail.getId() + "," + detail.getRedirectsTo().getId() + "\n");
				}
				
				
			} else if (detail.getNamespace() == SiteInfo.CATEGORY_KEY) {
				
				if (detail.getRedirectsTo() == null) {
					
					DbIntList categoryParents = buildIntList(detail.getParentCategories()) ;
					write(detail.getId(),categoryParents, categoryParentsWriter) ;
					
					DbIntList childArticles = buildIntList(detail.getChildArticles()) ;
					write(detail.getId(),childArticles, childArticlesWriter) ;
					
					DbIntList childCategories = buildIntList(detail.getChildCategories()) ;
					write(detail.getId(),childCategories, childCategoriesWriter) ;
					
					
				} else {
					
					
					
					//TODO: oops, no clean way of dealing with category redirects
				}
				
			}
				
			
			
			
			
		}
		
		
		pageWriter.close() ;
		
		articleParentsWriter.close();
		categoryParentsWriter.close() ;
		childArticlesWriter.close() ;
		childCategoriesWriter.close() ;
		
		pageLinkInWriter.close();
		pageLinkOutWriter.close();
		
		redirectSourcesByTargetWriter.close() ;
		redirectTargetsBySourceWriter.close() ;
		
		sentenceSplitsWriter.close();
		pageLabelWriter.close();
		
	}
	
	private void write(Integer id, Record record, BufferedWriter writer) throws IOException {
		
		ByteArrayOutputStream outStream = new ByteArrayOutputStream() ;

		CsvRecordOutput cro = new CsvRecordOutput(outStream) ;
		cro.writeInt(id, "pageId") ;
		record.serialize(cro) ;

		writer.write(outStream.toString("UTF-8")) ;
	}
	
	
	private DbPage buildPage(PageDetail detail, PageDepthSummary depth) {
		
		DbPage dbPage = new DbPage() ;
		dbPage.setType(getType(detail).ordinal());
		dbPage.setTitle(detail.getTitle().toString());
		
		if (depth != null && depth.getDepth() != null)
			dbPage.setDepth(depth.getDepth());
		else
			dbPage.setDepth(-1);
		
		return dbPage ;
	}
	
	private DbIntList buildIntList(List<PageSummary> summaries) {
		
		ArrayList<Integer> ids = new ArrayList<Integer>() ;
		for (PageSummary summary:summaries)
			ids.add(summary.getId()) ;
		
		return new DbIntList(ids) ;
	}
	
	private DbIntList buildIntList(Collection<Integer> values) {
		
		ArrayList<Integer> ints = new ArrayList<Integer>() ;
		for (Integer value:values)
			ints.add(value) ;
		
		return new DbIntList(ints) ;
	}
	
	private DbLabelForPageList buildLabelList(PageDetail page) {
		
		ArrayList<DbLabelForPage> dbLabels = new ArrayList<DbLabelForPage>() ;
		
		Set<CharSequence> redirectTitles = new HashSet<CharSequence>() ;
		for (PageSummary redirect:page.getRedirects())
			redirectTitles.add(redirect.getTitle()) ;
		
		for (Map.Entry<CharSequence, LabelSummary>e:page.getLabels().entrySet()) {
			
			CharSequence text = e.getKey() ;
			LabelSummary detail = e.getValue() ;
			
			DbLabelForPage label = new DbLabelForPage() ;
			label.setText(text.toString());
			label.setLinkDocCount(detail.getDocCount());
			label.setLinkOccCount(detail.getOccCount());
			
			label.setFromRedirect(redirectTitles.contains(text));
			label.setFromTitle(page.getTitle().equals(text));
			
			dbLabels.add(label) ;
			//TODO: no idea how we do isPrimary (whether the current page is the most common destination for the given label)
			
		}
		
		Collections.sort(dbLabels, labelComparator) ;
		
		return new DbLabelForPageList(dbLabels) ;
	}
	
	private DbLinkLocationList buildLinkLocationList(List<LinkSummary> summaries) {
		
		ArrayList<DbLinkLocation> links = new ArrayList<DbLinkLocation>() ;
		for (LinkSummary summary:summaries) {
			DbLinkLocation link = new DbLinkLocation() ;
			link.setLinkId(summary.getId());
			
			ArrayList<Integer> sentenceIndexes = new ArrayList<Integer>() ;
			sentenceIndexes.addAll(summary.getSentenceIndexes()) ;
			
			link.setSentenceIndexes(sentenceIndexes);
			
			links.add(link) ;
		}
		
		return new DbLinkLocationList(links) ;
	}
	
	private PageType getType(PageDetail detail) {
		
		if (detail.getNamespace() == SiteInfo.MAIN_KEY) {
			
			if (detail.getRedirectsTo() == null) {
				
				//TODO: disambig pages
				return PageType.article ;
				
			} else {
				return PageType.redirect ;
			}
			
		} else if (detail.getNamespace() == SiteInfo.CATEGORY_KEY) {
			
			if (detail.getRedirectsTo() == null) {
				return PageType.category ;
			} else {
				//TODO: oops, we don't have a good way to deal with redirects of categories
				return PageType.invalid ;
			}
		} else if (detail.getNamespace() == SiteInfo.TEMPLATE_KEY) {
			return PageType.template ;
		} else {
			return PageType.invalid ;
		}
	}
	
	
	
	private BufferedWriter createWriter(String fileName) throws IOException {
		
		FileSystem fs = getDir().getFileSystem(new Configuration()) ;
		
		FSDataOutputStream stream = fs.create(new Path(getDir() + Path.SEPARATOR + fileName)) ;
		OutputStreamWriter streamWriter = new OutputStreamWriter(stream) ;
			
		return new BufferedWriter(streamWriter) ;
	}
	
	private Path getMainAvroResultPath(Step step) throws IOException {
		
		FileSystem fs = step.getDir().getFileSystem(new Configuration()) ;
		
		FileStatus[] fileStatuses = fs.listStatus(step.getDir(), new PathFilter() {
			public boolean accept(Path path) {				
				return path.getName().startsWith("part-") ;
			}
		}) ;
		
		if (fileStatuses.length == 0)
			throw new IOException("Could not locate main result file in " + step.getDir()) ;
		
		if (fileStatuses.length > 1)
			throw new IOException("Too many result files (so too many reducers) in " + step.getDir()) ;
		
		return fileStatuses[0].getPath() ;
	}
	
	
}